package com.saikai.ptt.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saikai.ptt.R
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.PresenceState
import com.saikai.ptt.ui.theme.SaikaiAmber
import com.saikai.ptt.ui.theme.SaikaiGreen
import com.saikai.ptt.ui.theme.SaikaiGrey
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import com.saikai.ptt.ui.theme.SaikaiRed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Home screen: who I am, who is out there, who I am talking to, and the
 * button.
 *
 * ### Why every section takes a flow instead of a value
 *
 * A peer's heartbeat arrives every five seconds. Read at the top and passed
 * down, each one would invalidate this function, and Compose would then have to
 * walk the whole tree comparing parameters to discover that only the list
 * changed. Read *inside* the section that displays it, the invalidation stops
 * at that section -- the talk button is not even asked.
 *
 * So [HomeScreen] takes the flows themselves. They are the same objects on
 * every composition, so this function composes once and is never invalidated by
 * data at all. That is what Task32's "心跳更新不得导致整页重组" asks for, and it
 * is checkable: put a recomposition counter in each section and hold a
 * conversation across the room -- only the list's counter moves.
 *
 * ### Colour is never the only signal
 *
 * Every state on this screen is carried three ways: a shape, a word, and a
 * colour (`docs/04_UI_UX.md` sections 3 and 11.3). The presence mark is a
 * filled disc, a hollow ring or a barred disc before it is green, grey or
 * amber, and the word next to it says the same thing again. Turn the screen
 * greyscale and nothing is lost.
 */
@Composable
fun HomeScreen(
    header: StateFlow<HomeHeader>,
    peers: StateFlow<PeerListState>,
    target: StateFlow<TargetState>,
    ptt: StateFlow<PttState>,
    onSelectPeer: (DeviceId) -> Unit,
    onPressTalk: () -> Unit,
    onReleaseTalk: () -> Unit,
    onSetServiceRunning: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    debugExtras: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderSection(header, onSetServiceRunning)
        debugExtras()

        Text(
            text = stringResource(R.string.home_peers_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PeerListSection(
            peers = peers,
            onSelectPeer = onSelectPeer,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        TargetSection(target)
        PttSection(ptt, onPressTalk, onReleaseTalk)
    }
}

// --- Header ---------------------------------------------------------------------------

/**
 * The active name, and whether the app can hear anybody.
 *
 * The connection line is not decoration: `docs/04_UI_UX.md` section 23 puts it
 * at the top of the screen precisely so that an empty device list has a visible
 * reason. When the service is simply not running, the line comes with the
 * button that starts it -- an empty screen with no way out is the worse failure.
 */
@Composable
private fun HeaderSection(
    header: StateFlow<HomeHeader>,
    onSetServiceRunning: (Boolean) -> Unit,
) {
    val state by header.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = state.activeUserName
                ?.let { stringResource(R.string.home_active_user, it) }
                ?: stringResource(R.string.home_no_active_user),
            style = MaterialTheme.typography.bodyLarge,
        )
        ConnectionLine(state.connection, onSetServiceRunning)
    }
}

@Composable
private fun ConnectionLine(
    connection: ConnectionState,
    onSetServiceRunning: (Boolean) -> Unit,
) {
    // Nothing is said when everything is working. A permanent "all is well"
    // banner is one more thing to read on a screen whose whole point is that
    // the user can act without reading it.
    if (connection == ConnectionState.READY) return

    val text = when (connection) {
        ConnectionState.STOPPED -> R.string.home_service_stopped
        ConnectionState.STARTING -> R.string.home_service_starting
        ConnectionState.STOPPING -> R.string.home_service_stopping
        ConnectionState.NO_NETWORK -> R.string.home_network_offline
        ConnectionState.FAILED -> R.string.home_service_failed
        ConnectionState.READY -> return
    }

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            // Only for the states the user can do something about. "No WiFi" is
            // not one of them -- the service is already running and offering to
            // start it again would be offering a fix that fixes nothing.
            if (connection == ConnectionState.STOPPED || connection == ConnectionState.FAILED) {
                TextButton(onClick = { onSetServiceRunning(true) }) {
                    Text(stringResource(R.string.home_service_start))
                }
            }
        }
    }
}

// --- Peers ----------------------------------------------------------------------------

@Composable
private fun PeerListSection(
    peers: StateFlow<PeerListState>,
    onSelectPeer: (DeviceId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by peers.collectAsState()

    when (val current = state) {
        // Section 24: an empty list is never an error. Nothing has gone wrong;
        // the app is listening and no one has answered.
        PeerListState.Searching -> EmptyMessage(
            title = R.string.home_no_peers,
            detail = R.string.home_searching,
            modifier = modifier,
        )

        PeerListState.Unavailable -> EmptyMessage(
            title = R.string.home_peers_unavailable,
            detail = null,
            modifier = modifier,
        )

        is PeerListState.Peers -> LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Keyed by identity, not position: a device that goes quiet and
            // leaves the list must not hand its row -- and its selection
            // highlight -- to whoever was below it.
            items(current.rows, key = { it.deviceId.value }) { row ->
                PeerRowItem(row = row, onSelect = { onSelectPeer(row.deviceId) })
            }
        }
    }
}

@Composable
private fun EmptyMessage(
    @StringRes title: Int,
    @StringRes detail: Int?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (detail != null) {
            Text(
                text = stringResource(detail),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One device.
 *
 * `selectable` rather than `clickable` so the platform announces "selected" to
 * a screen reader instead of leaving that to the border, and so the whole row
 * is the touch target rather than the text inside it.
 *
 * A busy device is an ordinary row and can be picked. Section 11.2: the word
 * comes from a heartbeat that is up to a few hundred milliseconds old, the
 * callee is the only one that really knows, and refusing here would refuse
 * calls that would have connected.
 */
@Composable
private fun PeerRowItem(row: PeerRow, onSelect: () -> Unit) {
    val stateText = stringResource(row.state.labelRes())
    val shape = RoundedCornerShape(10.dp)

    Surface(
        shape = shape,
        tonalElevation = if (row.selected) 3.dp else 0.dp,
        color = if (row.selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                width = if (row.selected) 2.dp else 1.dp,
                color = if (row.selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .selectable(selected = row.selected, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PresenceMark(state = row.state, description = stateText)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.userName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The selection is said in words as well as drawn, for the same
            // reason the presence state is (section 12: "不应只依赖颜色").
            if (row.selected) {
                Text(
                    text = stringResource(R.string.home_selected),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The status mark: a shape first, a colour second.
 *
 * Filled disc, hollow ring, barred disc -- three silhouettes that survive a
 * greyscale screenshot, which is how section 11.3's requirement is actually
 * tested. Drawn rather than taken from an icon font because the product needs
 * exactly three shapes and a dependency on the Material icon set costs more
 * than thirty lines of canvas.
 */
@Composable
private fun PresenceMark(state: PresenceState, description: String) {
    val colour = when (state) {
        PresenceState.ONLINE, PresenceState.DISCOVERED -> SaikaiGreen
        PresenceState.BUSY -> SaikaiAmber
        PresenceState.COMMUNICATING -> SaikaiRed
        PresenceState.OFFLINE -> SaikaiGrey
    }

    Canvas(
        modifier = Modifier
            .size(20.dp)
            .semantics { contentDescription = description },
    ) {
        val radius = size.minDimension / 2f
        when (state) {
            // Heard from, but the periodic channel is not proven yet: an
            // outline, because it is not quite the same claim as ONLINE.
            PresenceState.DISCOVERED, PresenceState.OFFLINE ->
                drawCircle(color = colour, radius = radius - 1.5f, style = Stroke(width = 3f))

            PresenceState.ONLINE -> drawCircle(color = colour, radius = radius)

            // In a call: a disc with a bar through it, the shape every device
            // on earth uses for "not now".
            PresenceState.BUSY, PresenceState.COMMUNICATING -> {
                drawCircle(color = colour, radius = radius)
                drawRect(
                    color = Color.White,
                    topLeft = Offset(
                        x = center.x - radius * 0.55f,
                        y = center.y - radius * 0.16f,
                    ),
                    size = Size(width = radius * 1.1f, height = radius * 0.32f),
                )
            }
        }
    }
}

@StringRes
private fun PresenceState.labelRes(): Int = when (this) {
    // Section 25: the UI says Online / Busy / Offline. DISCOVERED is an
    // internal distinction about which packet proved liveness, and a user has
    // no use for it.
    PresenceState.DISCOVERED, PresenceState.ONLINE -> R.string.peer_state_online
    PresenceState.BUSY -> R.string.peer_state_busy
    PresenceState.COMMUNICATING -> R.string.peer_state_communicating
    PresenceState.OFFLINE -> R.string.peer_state_offline
}

// --- Target and button ----------------------------------------------------------------

@Composable
private fun TargetSection(target: StateFlow<TargetState>) {
    val state by target.collectAsState()

    Text(
        text = state.userName
            ?.let { stringResource(R.string.home_target, it) }
            ?: stringResource(R.string.home_target_none),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PttSection(
    ptt: StateFlow<PttState>,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val state by ptt.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // One line above the button, and only when there is something to say.
        // A message from the last attempt outranks the phase: "相手は通話中です"
        // is the answer to what the user just did, and the phase behind it is
        // already back to idle (sections 21.2 and 21.3).
        val note = state.message?.labelRes()
        if (note != null) {
            Text(
                text = stringResource(note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        } else if (state.phase == PttPhase.RECEIVING) {
            Text(
                text = state.peerName
                    ?.let { stringResource(R.string.ptt_receiving, it) }
                    ?: stringResource(R.string.ptt_receiving_unknown),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        PttButton(state = state, onPress = onPress, onRelease = onRelease)
    }
}

/**
 * Press and hold.
 *
 * Deliberately not a `Button`. A button's press interaction is cancelled when
 * the finger drifts outside its bounds, and section 13 asks for the opposite:
 * once the finger is down the transmission continues until it is lifted, no
 * matter how far it slides. So the gesture is read directly -- one down, then
 * every subsequent event ignored until no pointer is left pressed.
 *
 * That loop is also what makes the release reliable, which matters more than
 * the press: a session that never ends holds the microphone, the wake lock and
 * the peer's attention. `awaitEachGesture` ends the loop on cancellation as
 * well as on lift, and both paths lead to the same call.
 *
 * Large on purpose -- 190dp, most of the width of a phone, reachable with a
 * thumb and findable without looking, because the product is a walkie-talkie
 * (section 4).
 */
@Composable
private fun PttButton(state: PttState, onPress: () -> Unit, onRelease: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    // The gesture loop is started once and never restarted, so everything it
    // reads has to be read through a holder rather than captured.
    //
    // This is not a style choice. Keying `pointerInput` on `enabled` would tear
    // the loop down the instant a press succeeds -- pressing moves the phase
    // from READY to REQUESTING, which clears `enabled` -- and the coroutine
    // waiting for the finger to lift would be cancelled with it. The release
    // would never be sent, and the microphone, the wake lock and the peer would
    // all be left where they were.
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val canStart by rememberUpdatedState(state.enabled)

    val live = state.phase == PttPhase.REQUESTING || state.phase == PttPhase.TRANSMITTING
    val colour = when {
        !state.enabled && !live -> MaterialTheme.colorScheme.surfaceVariant
        live || held -> SaikaiRed
        else -> SaikaiGreen
    }
    val label = when (state.phase) {
        PttPhase.REQUESTING -> R.string.ptt_requesting
        PttPhase.TRANSMITTING -> R.string.ptt_release_to_end
        PttPhase.RECEIVING -> R.string.ptt_receiving_busy
        PttPhase.READY -> R.string.ptt_hold_to_talk
        PttPhase.UNAVAILABLE -> R.string.ptt_unavailable
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(190.dp)
            .clip(CircleShape)
            .background(colour)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Read at the moment of the press, not when the loop was
                    // built: whether this device can start talking is a fact
                    // about now.
                    val starting = canStart
                    if (starting) {
                        held = true
                        press()
                    }
                    // Movement is not consulted at all -- no bounds check, no
                    // slop threshold. This is the whole reason the gesture is
                    // hand-written rather than a Button's: section 13 asks that
                    // a finger sliding across the screen keep transmitting.
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    if (starting) {
                        held = false
                        release()
                    }
                }
            },
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = if (state.enabled || live) Color.White else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@StringRes
private fun PttMessage.labelRes(): Int = when (this) {
    PttMessage.TARGET_BUSY -> R.string.ptt_message_target_busy
    PttMessage.NO_RESPONSE -> R.string.ptt_message_no_response
    PttMessage.NO_NAME -> R.string.ptt_message_no_name
    PttMessage.NO_MICROPHONE -> R.string.ptt_message_no_microphone
    PttMessage.APP_NOT_VISIBLE -> R.string.ptt_message_app_not_visible
    PttMessage.UNREACHABLE -> R.string.ptt_message_unreachable
    PttMessage.ALREADY_IN_SESSION -> R.string.ptt_message_already_in_session
    PttMessage.INTERRUPTED -> R.string.ptt_message_interrupted
}

// --- Preview --------------------------------------------------------------------------

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun HomeScreenPreview() {
    val rows = listOf(
        PeerRow(DeviceId.random(), "山田", PresenceState.ONLINE, selected = true),
        PeerRow(DeviceId.random(), "倉庫", PresenceState.DISCOVERED, selected = false),
        PeerRow(DeviceId.random(), "保安", PresenceState.BUSY, selected = false),
    )
    SaikaiPttTheme {
        HomeScreen(
            header = MutableStateFlow(HomeHeader("田中", ConnectionState.READY)),
            peers = MutableStateFlow(PeerListState.Peers(rows)),
            target = MutableStateFlow(TargetState(rows[0].deviceId, "山田")),
            ptt = MutableStateFlow(PttState(PttPhase.READY, "山田", null)),
            onSelectPeer = {},
            onPressTalk = {},
            onReleaseTalk = {},
            onSetServiceRunning = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun HomeScreenOfflinePreview() {
    SaikaiPttTheme {
        HomeScreen(
            header = MutableStateFlow(HomeHeader(null, ConnectionState.NO_NETWORK)),
            peers = MutableStateFlow(PeerListState.Unavailable),
            target = MutableStateFlow(TargetState(null, null)),
            ptt = MutableStateFlow(PttState(PttPhase.UNAVAILABLE, null, PttMessage.TARGET_BUSY)),
            onSelectPeer = {},
            onPressTalk = {},
            onReleaseTalk = {},
            onSetServiceRunning = {},
        )
    }
}
