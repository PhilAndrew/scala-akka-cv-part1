package com.beachape.video

import akka.actor.{ ActorLogging, ActorSystem, DeadLetterSuppression, Props }
import akka.stream.Materializer
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{ Cancel, Request }
import akka.stream.scaladsl.{ Sink, Source }
import org.bytedeco.javacpp.opencv_core._
import org.bytedeco.javacpp.opencv_videoio
import org.bytedeco.javacpp.opencv_videoio.VideoCapture
import org.bytedeco.javacv.{ Frame, FrameGrabber, OpenCVFrameGrabber }
import org.bytedeco.javacv.FrameGrabber.ImageMode

/**
 * Created by Lloyd on 2/13/16.
 */

object Webcam {

  /**
   * Builds a Frame [[Source]]
   *
   * @param deviceId device ID for the webcam
   * @param dimensions
   * @param bitsPerPixel
   * @param imageMode
   * @param system ActorSystem
   * @return a Source of [[Frame]]s
   */
  def source(
    deviceId: Int,
    dimensions: Dimensions,
    bitsPerPixel: Int = CV_8U,
    imageMode: ImageMode = ImageMode.COLOR
  )(implicit system: ActorSystem): Source[Mat, Unit] = {
    val props = Props(
      new WebcamFramePublisher(
        deviceId = deviceId,
        imageWidth = dimensions.width,
        imageHeight = dimensions.height,
        bitsPerPixel = bitsPerPixel,
        imageMode = imageMode
      )
    )
    val webcamActorRef = system.actorOf(props)
    val webcamActorPublisher = ActorPublisher[Mat](webcamActorRef)

    Source.fromPublisher(webcamActorPublisher)
  }

  // Building a started grabber seems finicky if not synchronised; there may be some freaky stuff happening somewhere.
  private def buildGrabber(
    deviceId: Int,
    imageWidth: Int,
    imageHeight: Int,
    bitsPerPixel: Int,
    imageMode: ImageMode
  ): VideoCapture = synchronized {
    val g = new VideoCapture(0)

    /*
         -   **CAP_PROP_POS_MSEC** Current position of the video file in milliseconds.
     -   **CAP_PROP_POS_FRAMES** 0-based index of the frame to be decoded/captured next.
     -   **CAP_PROP_POS_AVI_RATIO** Relative position of the video file: 0 - start of the
         film, 1 - end of the film.
     -   **CAP_PROP_FRAME_WIDTH** Width of the frames in the video stream.
     -   **CAP_PROP_FRAME_HEIGHT** Height of the frames in the video stream.
     -   **CAP_PROP_FPS** Frame rate.
     -   **CAP_PROP_FOURCC** 4-character code of codec.
     -   **CAP_PROP_FRAME_COUNT** Number of frames in the video file.
     -   **CAP_PROP_FORMAT** Format of the Mat objects returned by retrieve() .
     -   **CAP_PROP_MODE** Backend-specific value indicating the current capture mode.
     -   **CAP_PROP_BRIGHTNESS** Brightness of the image (only for cameras).
     -   **CAP_PROP_CONTRAST** Contrast of the image (only for cameras).
     -   **CAP_PROP_SATURATION** Saturation of the image (only for cameras).
     -   **CAP_PROP_HUE** Hue of the image (only for cameras).
     -   **CAP_PROP_GAIN** Gain of the image (only for cameras).
     -   **CAP_PROP_EXPOSURE** Exposure (only for cameras).
     -   **CAP_PROP_CONVERT_RGB** Boolean flags indicating whether images should be converted
         to RGB.
     -   **CAP_PROP_WHITE_BALANCE** Currently unsupported
     -   **CAP_PROP_RECTIFICATION** Rectification flag for stereo cameras (note: only supported
         by DC1394 v 2.x backend currently)
     */

    g.set(opencv_videoio.CAP_PROP_FRAME_WIDTH, imageWidth)
    g.set(opencv_videoio.CAP_PROP_FRAME_HEIGHT, imageHeight)
    g
  }

  /**
   * Actor that backs the Akka Stream source
   */
  private class WebcamFramePublisher(
      deviceId: Int,
      imageWidth: Int,
      imageHeight: Int,
      bitsPerPixel: Int,
      imageMode: ImageMode
  ) extends ActorPublisher[Mat] with ActorLogging {

    private implicit val ec = context.dispatcher

    // Lazy so that nothing happens until the flow begins
    private lazy val grabber = buildGrabber(
      deviceId = deviceId,
      imageWidth = imageWidth,
      imageHeight = imageHeight,
      bitsPerPixel = bitsPerPixel,
      imageMode = imageMode
    )

    def receive: Receive = {
      case _: Request => emitFrames()
      case Continue => emitFrames()
      case Cancel => onCompleteThenStop()
      case unexpectedMsg => log.warning(s"Unexpected message: $unexpectedMsg")
    }

    private def emitFrames(): Unit = {
      if (isActive && totalDemand > 0) {
        /*
          Grabbing a frame is a blocking I/O operation, so we don't send too many at once.
         */
        graphFrame().foreach(onNext)
        if (totalDemand > 0) {
          self ! Continue
        }
      }
    }

    private def graphFrame(): Option[Mat] = {
      val frame = new Mat()
      grabber.read(frame)
      Option(frame)
    }
  }

  private case object Continue extends DeadLetterSuppression

}

