package io.github.bertpinoy.climbnamer.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Karoo events as a Flow (default params). */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val id: String = addConsumer<T> { trySend(it) }
    awaitClose { removeConsumer(id) }
}

/** Streaming values of a Karoo data type as a Flow. */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val id: String = addConsumer<OnStreamState>(OnStreamState.StartStreaming(dataTypeId)) { trySend(it.state) }
    awaitClose { removeConsumer(id) }
}
