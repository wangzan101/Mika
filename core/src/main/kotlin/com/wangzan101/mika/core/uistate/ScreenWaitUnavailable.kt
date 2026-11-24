package com.wangzan101.mika.core.uistate

/** The wait never started: this is an environment/precondition failure, not a deadline. */
class ScreenWaitUnavailable(val code: String, val detail: String) : Exception(detail)