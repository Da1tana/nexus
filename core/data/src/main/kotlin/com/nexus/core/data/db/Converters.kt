package com.nexus.core.data.db

import androidx.room.TypeConverter
import com.nexus.core.model.ActionKind
import com.nexus.core.model.MessageDirection
import com.nexus.core.model.MessageStatus
import com.nexus.core.model.ReplyCapability

/** Перечисления хранятся по имени — так SQL-запросы читаемы, а порядок констант можно менять. */
class Converters {
    @TypeConverter fun fromStatus(value: MessageStatus): String = value.name
    @TypeConverter fun toStatus(value: String): MessageStatus =
        MessageStatus.entries.firstOrNull { it.name == value } ?: MessageStatus.UNKNOWN

    @TypeConverter fun fromDirection(value: MessageDirection): String = value.name
    @TypeConverter fun toDirection(value: String): MessageDirection = MessageDirection.valueOf(value)

    @TypeConverter fun fromReply(value: ReplyCapability): String = value.name
    @TypeConverter fun toReply(value: String): ReplyCapability =
        ReplyCapability.entries.firstOrNull { it.name == value } ?: ReplyCapability.NONE

    @TypeConverter fun fromActionKind(value: ActionKind): String = value.name
    @TypeConverter fun toActionKind(value: String): ActionKind =
        ActionKind.entries.firstOrNull { it.name == value } ?: ActionKind.OTHER
}
