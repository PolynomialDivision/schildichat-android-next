# Android Conversation Notifications: SchildiChat vs. Signal vs. Telegram

Investigation notes comparing how SchildiChat, Signal, and Telegram integrate with Android's
Conversation Notifications system (Android 11+ long-lived shortcuts, `MessagingStyle`,
`NotificationChannel.setConversationId`, Priority Conversations). Written to answer: does
SchildiChat only expose chats with custom notification settings as Android conversations, and
should it expose more/all active chats automatically?

Sources: `schildichat-android-next` repo (recent commits `77ff961f0`, `cb971765`), Signal-Android
source at `/home/nick/Signal-Android`, Telegram source at `/home/nick/Telegram`.

## Concept clarification

| Concept | What it actually does |
|---|---|
| **Ordinary notification** | `NotificationCompat.Builder` with a channel, text, icon. No conversation semantics at all. |
| **`MessagingStyle`** | Makes the notification *look* like a chat thread (sender avatars, message bubbles inside the notification). By itself, does **not** make Android treat it as a "Conversation." |
| **Attaching a shortcut** (`setShortcutId`/`setShortcutInfo`) | Points the notification at a `ShortcutInfo`. Combined with `MessagingStyle`, **this is what actually makes Android recognize the notification as a Conversation** — grouped in the dedicated "Conversations" section of the shade, bubble-eligible, long-press offers a "Priority" toggle. Per Android's own docs, the shortcut must be *long-lived* (`setLongLived(true)`) and not disabled. |
| **A real per-conversation `NotificationChannel`** | A distinct `NotificationChannel` object, optionally with `setConversationId(parentChannelId, conversationId)`. This does **not** create the conversation — it's about letting **Settings** (System Settings → Notifications → Conversations) demultiplex and manage that one conversation's channel-level settings (sound/importance/DND behavior) independently, especially useful when many conversations otherwise share one channel. |
| **Automatically exposing a chat as a conversation** | The product decision of *when* to do the shortcut+MessagingStyle dance — for every chat, only recently active ones, only on demand, etc. |
| **Priority Conversation** | A **user-controlled** Android setting (long-press a conversation notification, or Settings → Conversations → mark Priority) that boosts DND-bypass, bubble behavior, and shade ranking. Apps cannot set this themselves — only surface the *eligibility* for the user to opt in. |

The key thing the original premise conflates: **conversation recognition** (shortcut +
MessagingStyle + shortcutId) vs. **dedicated per-room channel + `setConversationId`**. These are
separable, and SchildiChat already does the first one for nearly every room.

## SchildiChat — verified

**Is it true SchildiChat only creates Android conversations for chats with custom notifications
enabled?** Half right. The recent commits (`77ff961f0` "Add per-room notification channel
persistence and manager", `cb971765` "Wire per-room channels into notification creation and
cleanup", both on `main`) do exactly that — but only for the **dedicated channel +
`setConversationId`** layer, not for basic conversation recognition, which was already automatic
before these commits.

- `libraries/push/api/.../RoomNotificationChannelManager.kt` — its own KDoc: *"Most rooms have no
  entry here and keep using the shared channel."*
- `DefaultRoomNotificationChannelManager.kt:57-58` — `getChannelIdForRoom` returns the **shared**
  channel unless `sessionStore.getRoomNotificationChannelSettings(roomId) != null`.
- `DefaultRoomNotificationChannelManager.kt:148` — the actual
  `setConversationId(sharedChannelId, shortcutId)` call, gated behind that same `settings != null`
  check.
- Trigger for creating a per-room channel: `onRoomNotificationSettingsChanged(...)`, called when
  the user explicitly customizes a room's sound/priority/preview in-app
  (`RoomNotificationChannelSettings` — sound, priority, preview toggle). Its own KDoc: *"distinct
  from the Matrix-level push rule mode (all/mentions/mute)."*

**But** — `NotificationCreator.kt:186-190` (`DefaultNotificationCreator`) sets
`.setShortcutId(createShortcutId(...))` and `MessagingStyle` and
`NotificationCompat.CATEGORY_MESSAGE` **unconditionally**, for every non-thread room notification,
regardless of custom settings. And the shortcut itself — the piece that actually satisfies
Android's Conversation-recognition requirement — is already created automatically for essentially
every active room:

- `DefaultNotificationConversationService.kt:110-124` (`onSendMessage`) builds a
  `ShortcutInfoCompat` with `.setLongLived(true)` and `SHORTCUT_CATEGORY_CONVERSATION`, pushed via
  `ShortcutManagerCompat.pushDynamicShortcut(...)`.
- Called from **two** places: `MessageComposerPresenter.kt:578` on every message **you** send, and
  `ScOnNotifiableEventReceivedExtensions.kt` → `OnNotifiableEventReceived.kt:34` on every
  **incoming** non-thread message, before the notification is even drawn.

**What actually triggers conversation creation, and is it every room or a subset?** SchildiChat
registers a real, long-lived Android shortcut for **every room with recent send/receive
activity** — that's already "automatic for all active chats," not gated by custom settings. What's
gated is only the fancier per-room `NotificationChannel` + `setConversationId` link, which affects
channel-level importance/sound and whether Settings can deep-link to that specific room. Whether
basic Conversation-shade recognition and the notification's own Priority long-press option require
that channel-level `setConversationId` too, versus working from shortcut+MessagingStyle alone, is
the one thing to verify on-device rather than assume — see "what to check next" below.

## Signal — verified

- `NotificationFactory.kt:246-247` sets `setShortcutId(...)` and `setLocusId(...)`
  **unconditionally** per conversation notification, same pattern as SchildiChat.
- But the underlying shortcuts themselves are **not** pushed for every conversation —
  `ConversationShortcutUpdateJob.java` runs periodically and pushes shortcuts for only the **top
  ~10 most recently active** conversations (`ShortcutManagerCompat.setDynamicShortcuts`), refreshed
  on conversation open/archive/pin, plus a ranking-update job on send (API 34+).
- Per-recipient dedicated `NotificationChannel` + `setConversationId` (`NotificationChannels.java:259-261`)
  is gated exactly like SchildiChat: only created for recipients with **custom per-recipient
  notification settings**.
- Mentions/replies: `NotificationStateProvider.kt` — a self-mention or reply-to-self can "break
  through mute" **only if the user opted into `ALWAYS_NOTIFY`** for mentions/replies on that
  conversation — i.e., stronger treatment is available but strictly user-opted-in, never silently
  overridden.

## Telegram — verified

- Does **not** use `setShortcutId`; uses the newer `builder.setShortcutInfo(shortcut)` overload
  instead — functionally equivalent for conversation recognition.
- Shortcuts are created **on-demand, per notification**, only when Android 29+ and bubbles are
  enabled and the chat isn't encrypted (secret chats are explicitly excluded) — then **removed from
  the dynamic shortcut list immediately after posting** (`NotificationsController.java:5719`).
  Notably, Telegram does not keep long-lived shortcuts sitting around between notifications the way
  Signal/SchildiChat do.
- Does **not** use `NotificationChannel.setConversationId()` anywhere — confirmed absent from the
  codebase. Instead it creates one throwaway `NotificationChannel` per dialog+topic combination
  (`"channel_" + dialogId + "_" + random`), grouped by chat type, and just relies on
  shortcut+MessagingStyle for conversation recognition.
- Mentions get separate tracking (`messageOwner.mentioned`) that can override per-dialog mute, but
  there's no distinct "priority tier" beyond standard channel importance.

## Comparison table

| | SchildiChat | Signal | Telegram |
|---|---|---|---|
| Shortcut+MessagingStyle+shortcutId, automatic for every active chat? | **Yes** (send + receive) | No — top ~10 recent only | Yes, but ephemeral (created/torn down per notification) |
| Dedicated per-chat `NotificationChannel`? | Only if user customized | Only if user customized | Always (throwaway, one per dialog+topic) |
| Uses `setConversationId`? | Yes, gated | Yes, gated | **No, never** |
| Mentions/replies bypass mute? | Not found in this pass | Yes, opt-in via `ALWAYS_NOTIFY` | Tracked, overrides mute automatically for @mentions |

## Answering the remaining questions

**Do Signal/Telegram automatically expose all active chats as Android conversations?** Neither
exposes literally *all* chats as long-lived conversations at all times. Signal caps it at ~10
most-recent via a background job; Telegram doesn't keep shortcuts around between notifications at
all. Both still get full conversation-notification treatment for whatever's currently generating a
notification, because — same as SchildiChat — the shortcut+MessagingStyle+shortcutId combination is
applied per-notification regardless of caps.

**Implementation strategy for SchildiChat:** SchildiChat is arguably already ahead of both here for
the "automatic" question, since it pushes real long-lived shortcuts unconditionally on send/receive
rather than capping at ~10 or tearing them down. The actual gap, if any, is narrower than "make more
things conversations" — it's whether you also want every room's *channel* individually
`setConversationId`-tagged so it shows up manageably in System Settings → Conversations even before
a notification exists. That would mean creating a channel per room unconditionally rather than only
for customized rooms.

**Preserving user control:** Priority Conversation is exclusively a user action in both Signal and
Telegram. Making more rooms channel-eligible only adds *more entries* the user can choose to mark
Priority; it doesn't touch who controls that switch.

**Platform limits:** Two real constraints if SchildiChat went further: (a)
`ShortcutManagerCompat.getMaxShortcutCountPerActivity()` caps dynamic shortcuts (Signal explicitly
designs around this with its top-10 job); (b) `NotificationChannel`s are **permanent** once created
— Android gives apps no automatic GC, so per-room channels for every room ever visited would
accumulate indefinitely unless already pruned (which `RoomNotificationChannelManager.pruneChannelsForSession`/
`clearRoomChannel` already do defensively for the customized-only set — that pruning logic would
need to scale if applied to every room).

## Encrypted rooms & mentions/replies

- **Encryption**: not a concern — `NotifiableMessageEvent` (used in both the shortcut-creation and
  notification-building paths) already carries post-decryption plaintext (room name, sender, body);
  the SDK does local decryption before either path runs. Nothing here needs new encrypted-room
  handling.
- **Mentions/replies getting stronger treatment without bypassing user control**: Signal's
  `ALWAYS_NOTIFY` pattern is the right model to copy — an explicit, opt-in per-room toggle ("always
  notify me for mentions/replies in this room") that only takes effect if the *user* enabled it,
  never a silent override. That's a Matrix-push-rule-level decision (whether to notify at all) and
  orthogonal to the Android-conversation-channel question above.

## Confirmed on-device: `setConversationId` is required, not just the shortcut

Tested directly: a notification from a room with **no** custom notification channel (shortcut +
MessagingStyle + shortcutId all present, per `NotificationCreator.kt`) does **not** appear under
Settings → Notifications → Conversations. Only rooms with a dedicated per-room channel (and thus
`setConversationId`) show up there.

This resolves the ambiguity from the first pass: shortcut+MessagingStyle alone gets you the
in-shade grouping/rendering benefits of `MessagingStyle`, but **not** the persistent,
user-manageable "Conversation" entry in system Settings, and therefore not a way for the user to
mark a non-customized room Priority ahead of time. That requires a real per-room
`NotificationChannel` with `setConversationId`, which today only exists for rooms the user has
already customized.

So the original premise holds for the part that matters most: to let users make *any* room a
Priority Conversation (not just ones they've already gone into settings and customized), SchildiChat
would need to create a per-room channel + `setConversationId` for every room that produces a
notification — not just customized ones. That directly re-opens the platform tradeoff from the
section above: doing this for every room means a `NotificationChannel` created (and needing pruning
on room-leave/logout, which the existing `RoomNotificationChannelManager` plumbing already knows how
to do for the customized-only set) for every room a user has ever received a message in, not just a
handful they explicitly customized.

A middle-ground worth considering: create the per-room channel + `setConversationId` **the first
time a room produces a notification** (mirroring the shortcut creation trigger that's already
automatic), using the same default sound/importance as the shared channel, rather than only when the
user opens per-room settings and changes something. The room keeps behaving identically to today
(same sound, same importance) until the user customizes it — but it also becomes visible and
markable as Priority in system Settings immediately, closing the gap this investigation found.
