package com.beachape

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.beachape.transform.{ Flip, MediaConversion }
import com.beachape.video.{ Dimensions, Webcam }
import org.bytedeco.javacv.CanvasFrame

object WebcamWindow extends App {
  import org.bytedeco.javacpp.opencv_video._
  import org.bytedeco.javacpp.opencv_videoio._

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  val canvas = new CanvasFrame("Webcam")
  //  //Set Canvas frame to close on exit
  canvas.setDefaultCloseOperation(javax.swing.JFrame.EXIT_ON_CLOSE)

  val imageDimensions = Dimensions(width = 640, height = 480)
  val webcamSource = Webcam.source(deviceId = 0, dimensions = imageDimensions)

  val graph = webcamSource
    //.map(MediaConversion.toMat) // most OpenCV manipulations require a Matrix
    .map(Flip.horizontal)
    .map(MediaConversion.toFrame) // convert back to a frame
    .map(canvas.showImage)
    .to(Sink.ignore)

  graph.run()

}