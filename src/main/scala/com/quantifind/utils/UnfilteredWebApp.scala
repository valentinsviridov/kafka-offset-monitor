package com.quantifind.utils

import unfiltered.util.Port
import com.quantifind.sumac.{ArgMain, FieldArgs}
import com.quantifind.utils.UnfilteredWebApp.Arguments
import unfiltered.jetty.SocketPortBinding

/**
  * Web app that serves static files from the resource directory and other stuff from the provided mapping
  * User: pierre
  * Date: 10/3/13
  */
trait UnfilteredWebApp[T <: Arguments] extends ArgMain[T] {

  def htmlRoot: String

  def setup(args: T): unfiltered.filter.Plan

  def afterStart() {}

  def afterStop() {}

  override def main(parsed: T) {
    unfiltered.jetty.Server
      .portBinding(SocketPortBinding(parsed.port, "0.0.0.0"))
      // whatever is not matched by our filter will be served from the resources folder (html, css, ...)
      .resources(getClass.getResource(htmlRoot))
      .plan(setup(parsed))
      .run(_ => afterStart(), _ => afterStop())
  }

}

object UnfilteredWebApp {

  trait Arguments extends FieldArgs {
    var port: Int = Port.any
  }

}
