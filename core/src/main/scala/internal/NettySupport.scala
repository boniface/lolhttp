package lol.http.internal

import fs2.{ Stream, Strategy, Chunk, Task, Pull, Sink, async }

import io.netty.channel.{
  Channel,
  ChannelFuture,
  SimpleChannelInboundHandler,
  ChannelHandlerContext }
import io.netty.handler.logging.{ LogLevel, LoggingHandler }
import io.netty.util.concurrent.{ GenericFutureListener }
import io.netty.buffer.{ Unpooled, ByteBuf }
import io.netty.handler.codec.http.{
  HttpObject,
  HttpContent,
  LastHttpContent,
  HttpMessage,
  HttpResponse,
  DefaultHttpContent,
  DefaultLastHttpContent,
  HttpClientCodec,
  HttpResponseEncoder,
  HttpRequestDecoder,
  HttpContentDecompressor,
  HttpMethod,
  HttpRequest,
  HttpUtil }

import scala.concurrent.{ Future, Promise }
import scala.collection.mutable.{ ListBuffer }
import collection.JavaConverters._

import lol.http._

private[http] object NettySupport {

  implicit class NettyChannelFuture(f: ChannelFuture) {
    def toFuture: Future[Channel] = {
      val p = Promise[Channel]
      f.addListener(new GenericFutureListener[ChannelFuture] {
        override def operationComplete(f: ChannelFuture) = {
          if(f.isSuccess) {
            p.success(f.channel)
          }
          else {
            p.failure(f.cause)
          }
        }
      })
      p.future
    }
    def toTask(implicit S: Strategy): Task[Channel] = {
      Task.async { cb =>
        try {
          f.addListener(new GenericFutureListener[ChannelFuture] {
            override def operationComplete(f: ChannelFuture) = {
              if(f.isSuccess) {
                cb(Right(f.channel))
              }
              else {
                cb(Left(f.cause))
              }
            }
          })
        }
        catch {
          case e: Throwable =>
            cb(Left(e))
        }
      }
    }
  }

  implicit class NettyByteBuffer(buffer: ByteBuf) {
    def toChunk: Chunk[Byte] = {
      val chunks = ListBuffer.empty[Chunk[Byte]]
      while(buffer.readableBytes > 0) {
        val bytes = Array.ofDim[Byte](buffer.readableBytes)
        buffer.readBytes(bytes)
        chunks += Chunk.bytes(bytes)
      }
      Chunk.concat(chunks)
    }
  }

  implicit class ChunkByteBuffer(chunk: Chunk[Byte]) {
    def toByteBuf: ByteBuf = Unpooled.wrappedBuffer(chunk.toArray)
  }

  implicit class BetterChannel(channel: Channel) {

    def runInEventLoop[A](a: => A): A = {
      val latch = new java.util.concurrent.CountDownLatch(1)
      @volatile var result: Option[A] = None
      channel.eventLoop.submit(new Runnable() {
        def run = {
          result = Some(a)
          latch.countDown
        }
      })
      latch.await
      result.get
    }

    def httpContentSink(implicit S: Strategy): Sink[Task,Byte] = {
      _.repeatPull(_.awaitOption.flatMap {
        case Some((chunk, h)) =>
          Pull.eval(
            if(channel.isOpen)
              channel.writeAndFlush(new DefaultHttpContent(chunk.toByteBuf)).toTask
            else
              Task.fail(Error.ConnectionClosed)
          ) as h
        case None =>
          Pull.eval(
            if(channel.isOpen)
              channel.writeAndFlush(new DefaultLastHttpContent()).toTask
            else
              Task.fail(Error.ConnectionClosed)
          ) >> Pull.done
      })
    }

    def bytesSink(implicit S: Strategy): Sink[Task,Byte] = {
      _.repeatPull(_.awaitOption.flatMap {
        case Some((chunk, h)) =>
          Pull.eval(
            if(channel.isOpen)
              channel.writeAndFlush(chunk.toByteBuf).toTask
            else
              Task.fail(Error.ConnectionClosed)
          ) as h
        case None =>
          Pull.done
      })
    }
  }

  object Netty {
    def clientConnection(channel: Channel, debug: Option[String])(implicit S: Strategy) = {
      debug.foreach(logger => channel.pipeline.addLast("Debug", new LoggingHandler(logger, LogLevel.INFO)))
      channel.pipeline.addLast("HttpClientCodec", new HttpClientCodec())
      channel.pipeline.addLast("HttpDecompress", new HttpContentDecompressor())
      new HttpConnection(channel, writeFirst = true)
    }

    def serverConnection(channel: Channel, debug: Option[String])(implicit S: Strategy) = {
      debug.foreach(logger => channel.pipeline.addLast("Debug", new LoggingHandler(logger, LogLevel.INFO)))
      channel.pipeline.addLast("HttpRequestDecoder", new HttpRequestDecoder())
      channel.pipeline.addLast("HttpResponseEncoder", new HttpResponseEncoder())
      new HttpConnection(channel, writeFirst = false)
    }
  }

  class HttpConnection(channel: Channel, writeFirst: Boolean)(implicit S: Strategy) {
    // At first we set the channel in auto read to get the first message
    channel.config.setAutoRead(true)

    val (messages, content, permits) = (for {
      // The HTTP messages buffer. We use an unboundedQueue here but
      // because we only 1 message at a time, the effective size will be 1.
      messages <- async.unboundedQueue[Task,Option[(HttpMessage,Boolean)]]
      // The content buffer. We use also an unboundedQueue here but
      // because we ask netty to stop to read as soon as we have one chunk,
      // so the effective size will be 1 as well.
      content <- async.unboundedQueue[Task,Option[Chunk[Byte]]]
      // Track usages. We only allow one message to be write/read at a time.
      permits <- async.semaphore[Task](1)
    } yield (messages, content, permits)).unsafeRun()

    // Each time we consume a chunk we ask the channel
    // to read the next message. When this chunk has been
    // enqueued no more data were available on the socket.
    // Because we are now consuming this chunk, we can inform the
    // socket that we are ready to receive new data.
    val contentStream =
      content.dequeue.evalMap { chunk => Task.delay(if(chunk.isDefined) channel.read()).map(_ => chunk) }

    channel.pipeline.addLast("HttpStreamHandler", new SimpleChannelInboundHandler[HttpObject]() {
      // Netty will call everything here on the eventLoop, so this code will be effectively
      // single threaded.

      // Mutable reference is safe here because the code is single threaded.
      // For performance reason we don't push a chunk to the queue each time
      // a message has been read by netty. We buffer up to the maximum chunk size.
      var buffer: Chunk[Byte] = Chunk.empty

      // Optimization for message with no content.
      var skipContent: Boolean = false
      def hasContent(message: HttpMessage) = message match {
        case req: HttpRequest if req.method == HttpMethod.GET || req.method == HttpMethod.HEAD => false
        case req: HttpRequest => HttpUtil.getContentLength(req, 0) > 0
        case _ => true
      }

      override def channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) = msg match {
        // New HTTP message, enqueue it in the message queue
        // disable autoread, so the message content will be pulled
        // and read a first chunk of content.
        case message: HttpMessage =>
          (if(hasContent(message)) {
            (for {
              _ <- messages.enqueue1(Some(message -> true))
              _ <- Task.delay(skipContent = false)
              _ <- Task.delay(channel.config.setAutoRead(false))
            } yield ())
          }
          else {
            (for {
              _ <- messages.enqueue1(Some(message -> false))
              _ <- Task.delay(skipContent = true)
            } yield ())
          }).unsafeRun()
        // We ignore the content and the last chunk has been received,
        // mark the connection ready for next message.
        case lastChunk: LastHttpContent if skipContent =>
          (if(writeFirst) permits.increment else permits.decrement).unsafeRun()
        // Should not happen, unless the client send us a GET/HEAD request
        // with a content body and we will ignore it anyawy
        case chunk: HttpContent if skipContent =>
        // Last chunk has been received, so it is the end of the
        // message content. Enqueue the rest of the buffer if needed,
        // enqueue the last chunk and enqueue a None to signal that it
        // is the end of the content stream. Reset the buffer, and enable
        // autoread to wait for the next message.
        case lastChunk: LastHttpContent =>
          (for {
            _ <- content.enqueue1(Some(buffer))
            _ <- content.enqueue1(Some(lastChunk.content.toChunk))
            _ <- content.enqueue1(None)
            _ <- Task.delay {
              buffer = Chunk.empty
              channel.config.setAutoRead(true)
            }
          } yield ()).unsafeRun()
        // A content chunk has been received. Add it to the buffer.
        case chunk: HttpContent =>
          buffer = Chunk.concat(Seq(buffer, chunk.content.toChunk))
      }

      // No more data available on the socket. Enqueue the buffered
      // data to the content queue and clear the buffer.
      override def channelReadComplete(ctx: ChannelHandlerContext) = {
        (for {
          _ <- content.enqueue1(Some(buffer))
          _ <- Task.delay(buffer = Chunk.empty)
        } yield ()).unsafeRun()
      }
    })

    channel.pipeline.addLast("CatchAll", new SimpleChannelInboundHandler[Any] {
      override def channelRead0(ctx: ChannelHandlerContext, msg: Any) = msg match {
        // If we have switched protocol, we now receive raw bytes buffer here.
        case msg: ByteBuf =>
          content.enqueue1(Some(msg.toChunk)).unsafeRun()
          // Stop reading automatically now, user code will pull the stream.
          channel.config.setAutoRead(false)
        case _ =>
          Panic.!!!(s"Missed $msg")
      }
      override def exceptionCaught(ctx: ChannelHandlerContext, e: Throwable) = {
        ctx.close()
        e match {
          case Panic(_) => throw e
          case e =>
        }
      }
    })

    // When the channel is closed we push None to the message
    // queue, to indicate the End Of Stream. We also push None
    // to the content queue to force incomplete stream to finish.
    lazy val closed: Task[Unit] = channel.closeFuture.toTask.flatMap { _ =>
      (for {
        _ <- messages.enqueue1(None)
        _ <- content.enqueue1(None)
      } yield ())
    }

    def isOpen: Boolean = channel.isOpen
    def close: Task[Unit] = Task.delay(if(channel.isOpen) channel.close())

    // Read one HTTP message along with its content stream. The
    // content stream must be read before the next message to be
    // available.
    def read(): Task[(HttpMessage,Stream[Task,Byte])] =
      messages.dequeue1.flatMap {
        case Some((message, true)) =>
          for {
            readers <- async.semaphore[Task](1) // Keep track of the number of readers
            eosReached <- async.signalOf[Task,Boolean](false) // Keep track of the End Of Stream
            messageStream =
              Stream.
                // The content stream can be read only once
                eval(readers.tryDecrement).flatMap {
                  case false =>
                    Stream.fail(Error.StreamAlreadyConsumed)
                  case true =>
                    contentStream.
                      // We read the queue until a None, that mark
                      // the content stream end.
                      evalMap { chunk => eosReached.set(chunk.isEmpty).map(_ => chunk) }.
                      takeWhile(_.isDefined).
                      // The stream of bytes
                      flatMap(chunk => Stream.chunk(chunk.get)).
                      // When user code finished to consume this stream, we need
                      // to drain the remaining content if the eos has not been
                      // reached yet.
                      onFinalize(for {
                        fullyRead <- eosReached.get
                        _ <- if(fullyRead) Task.now(()) else contentStream.takeWhile(_.isDefined).drain.run
                        _ <- if(writeFirst) permits.increment else permits.decrement
                      } yield ())
                }
          } yield (message, messageStream)
        case Some((message, false)) =>
          Task.now((message, Stream.empty))
        case _ =>
          Task.fail(Error.ConnectionClosed)
      }

    // Write an HTTP message along with its content to the channel.
    def write(message: HttpMessage, contentStream: Stream[Task,Byte]): Task[Unit] = for {
      _ <- if(writeFirst) permits.decrement else permits.increment
      _ <- if(channel.isOpen) Task.delay(channel.writeAndFlush(message)) else Task.fail(Error.ConnectionClosed)
      _ <- (contentStream to channel.httpContentSink).run
      _ <- Task.delay(if(message.isInstanceOf[HttpResponse] && HttpUtil.getContentLength(message, -1) < 0) channel.close())
    } yield ()

    // Upgrade the connection to a plain TCP connection: we deregister
    // all the netty HTTP pipeline.
    def upgrade(): Task[Stream[Task,Byte]] =
      for {
        _ <- permits.decrement
        _ <- messages.enqueue1(None)
        in <- Task.delay {
          channel.runInEventLoop[Stream[Task,Byte]] {
            channel.pipeline.names.asScala.filter(_.startsWith("Http")).foreach(channel.pipeline.remove)
            // Read the next message
            channel.read()
            // The incoming stream
            contentStream.
              takeWhile(_.isDefined).
              flatMap(chunk => Stream.chunk(chunk.get))
          }
        }
      } yield (in)

    def writeBytes = channel.bytesSink
  }

}
