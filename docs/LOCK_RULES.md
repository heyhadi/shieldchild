# Lock rules

The single description of when ShieldChild locks and what a parent can lift. Every row has a
test in `app/src/test/java/com/shieldchild/app/engine/LockRulesTest.kt`; change both together.
The app applies them in one place, `engine/LockDecider`: every part that locks asks it, and
`LockDeciderTest` checks they all get the same answer.

## Locks, strongest first

| # | Lock | When it applies | What still works |
|---|---|---|---|
| 1 | **Instant lock** | Parent turns it on, or ShieldChild detects tampering (clock change, settings) | Calls only. No essential apps. |
| 2 | **Bedtime** | Inside the bedtime window, every day (also on unlimited days) | Calls, essential apps marked "allow at bedtime" |
| 3 | **Daily limit** | Today's use ≥ limit + bonus. Not on unlimited days. | Calls, all essential apps |
| 4 | **App blocked** | App set to "blocked completely" | Everything else |
| 5 | **App out of time** | That app's use today ≥ its own limit | Everything else |

1–3 lock the whole phone; only the strongest one that applies is shown. Essential apps never get an app lock.

## What counts as use

- An app is in use while it is on screen, including in picture-in-picture: a video playing in a
  small window over Home or over another app counts toward that app's limit until the window closes.
- The phone's daily total is time with any app on screen, so a PiP over another app counts once.
  The home screen, ShieldChild, System UI and keyboards don't count. Screen off doesn't count.

## What each parent action lifts

| Parent action | Instant lock | Bedtime | Daily limit | App blocked | App out of time | How long |
|---|---|---|---|---|---|---|
| **Use the phone myself** | ✅ | ✅ | ✅ | ❌ | ✅ | Until screen off, 5 min idle or 10 min total. Not counted against the child. |
| **15 / 30 / 60 min, or a code** | ✅, returns after | ✅ if it's the lock showing | ✅ (pause only, limit unchanged) | ❌ | ✅ that app only | Those minutes |
| **Rest of today** | ✅ until midnight | ✅ until bedtime ends | ✅ until midnight | ❌ | ✅ until midnight | As listed |
| **Turn off instant lock** | ✅ | ❌ | ❌ (15 min if already reached) | ❌ | ❌ | Until turned on again |
| **Dashboard + bonus** | ❌ | ❌ | ✅ raises today's limit | ❌ | ❌ | Until midnight |
| **Unblock in dashboard** | ❌ | ❌ | ❌ | ✅ | ❌ | Until blocked again |

- Minutes lift only what blocks the phone now: minutes given for the daily limit don't lift a bedtime that starts during them.
- Turning instant lock on (or a tamper lock) ends a parent session and cancels any time left from an earlier grant.
- At midnight the bonus, app pauses and the parent's excluded time reset. A whole-phone grant still runs to its end, even past midnight.
