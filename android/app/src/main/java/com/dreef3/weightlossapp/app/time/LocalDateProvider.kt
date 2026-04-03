package com.dreef3.weightlossapp.app.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LocalDateProvider(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun today(): LocalDate = LocalDate.now(zoneId)

    fun dateFor(instant: Instant): LocalDate = instant.atZone(zoneId).toLocalDate()
}
