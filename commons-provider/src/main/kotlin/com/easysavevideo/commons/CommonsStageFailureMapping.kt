package com.easysavevideo.commons

import com.easysavevideo.discovery.CommonsStageFailure

/** Explicit enum mapping only: never inspect exception text, class names, URLs or headers. */
fun CommonsError.stageFailure(): CommonsStageFailure = when (this) {
    CommonsError.CANCELLED -> CommonsStageFailure.CANCELLED
    CommonsError.DEADLINE_EXCEEDED -> CommonsStageFailure.DEADLINE_EXCEEDED
    CommonsError.BUSY -> CommonsStageFailure.BUSY
    CommonsError.RATE_LIMITED -> CommonsStageFailure.RATE_LIMITED
    CommonsError.INVALID_RESPONSE -> CommonsStageFailure.INVALID_RESPONSE
    CommonsError.RESPONSE_TOO_LARGE -> CommonsStageFailure.RESPONSE_TOO_LARGE
    CommonsError.UNSAFE_URL -> CommonsStageFailure.UNSAFE_ADDRESS
    CommonsError.REDIRECT_REJECTED -> CommonsStageFailure.REDIRECT_REJECTED
    CommonsError.SERVICE_DENIED -> CommonsStageFailure.SERVICE_DENIED
    CommonsError.UNSUPPORTED_FILTER -> CommonsStageFailure.UNSUPPORTED_FILTER
    CommonsError.INVALID_REQUEST, CommonsError.INVALID_CURSOR -> CommonsStageFailure.INVALID_REQUEST
    CommonsError.SERVICE_ERROR -> CommonsStageFailure.SERVICE_FAILURE
    CommonsError.DISABLED, CommonsError.CONTACT_REQUIRED, CommonsError.VERSION_REQUIRED,
    CommonsError.TRANSPORT_UNAVAILABLE -> CommonsStageFailure.CONFIGURATION
}
