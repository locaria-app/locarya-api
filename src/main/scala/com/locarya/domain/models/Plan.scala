package com.locarya.domain.models

sealed trait Plan
object Plan:
  case object Freemium extends Plan
  case object Premium  extends Plan
