package io.github.rafaeldatsch4afk.mibandfaces.band

import io.github.rafaeldatsch4afk.mibandfaces.proto.Clock
import io.github.rafaeldatsch4afk.mibandfaces.proto.Command
import io.github.rafaeldatsch4afk.mibandfaces.proto.Date
import io.github.rafaeldatsch4afk.mibandfaces.proto.System
import io.github.rafaeldatsch4afk.mibandfaces.proto.Time
import java.util.Calendar
import java.util.TimeZone
import io.github.rafaeldatsch4afk.mibandfaces.proto.TimeZone as ProtoTimeZone

/** Comando de acerto de hora (XiaomiSystemService.setCurrentTime no Gadgetbridge). */
object XiaomiClock {
    fun buildSetTimeCommand(now: Calendar, is24Hour: Boolean): Command {
        val clock = Clock.newBuilder()
            .setTime(
                Time.newBuilder()
                    .setHour(now.get(Calendar.HOUR_OF_DAY))
                    .setMinute(now.get(Calendar.MINUTE))
                    .setSecond(now.get(Calendar.SECOND))
                    .setMillisecond(now.get(Calendar.MILLISECOND)),
            )
            .setDate(
                Date.newBuilder()
                    .setYear(now.get(Calendar.YEAR))
                    .setMonth(now.get(Calendar.MONTH) + 1)
                    .setDay(now.get(Calendar.DATE)),
            )
            .setTimezone(
                ProtoTimeZone.newBuilder()
                    // A banda conta os deslocamentos em blocos de 15 minutos.
                    .setZoneOffset(now.get(Calendar.ZONE_OFFSET) / 1000 / 60 / 15)
                    .setDstOffset(now.get(Calendar.DST_OFFSET) / 1000 / 60 / 15)
                    .setName(now.timeZone.id),
            )
            .setIsNot24Hour(!is24Hour)
            .build()
        return Command.newBuilder()
            .setType(Commands.SYSTEM)
            .setSubtype(Commands.SYSTEM_CLOCK)
            .setSystem(System.newBuilder().setClock(clock))
            .build()
    }

    fun now(): Calendar = Calendar.getInstance(TimeZone.getDefault())
}
