# Donut Auction Manager (Fabric 1.21.11, client-side)

A read-only helper for running an auction. Players bid by paying you with the server's normal payment command.
The mod reads **server-confirmed** payment messages in your chat, tracks the highest bid, runs a timer and gives you
a winner summary. It never sends chat, payments or items by itself: announcements are copied to your clipboard and you
paste and send them yourself.

## Built-in payment format
One format ships by default, taken from a real message: `<name> paid you $ <amount>` (amount like `1`, `1,500`, `2.5K`, `1M`).
Anything else that contains `$` goes to the manual review queue instead of being guessed. If your real messages look
different (other wording, amounts without `$`, a rank before the name), add or change a format as described below.

### Get your formats (about 5 minutes)
1. `/aucmod capture on`, then have a friend (or alt) pay you once, send a payment yourself, and try a failing one
   (not enough money, invalid amount, etc.).
2. `/aucmod capture off`. Open `config/donut-auction-manager/capture.log`. Each line is
   `time <TAB> channel <TAB> plain text <TAB> styled text`. Channels: `SYSTEM` (server chat message),
   `ACTIONBAR`, `CHAT` (player chat, never used for payments).
3. Add a format to `config.json` for each message type you saw, copying the wording exactly:

```json
{
  "id": "my-incoming",
  "kind": "INCOMING_CONFIRMED",
  "source": "system",
  "target": "plain",
  "regex": "<exact message with (?<payer>...) and (?<amount>...) in place of the name and amount>"
}
```
   - `kind`: `INCOMING_CONFIRMED`, `OUTGOING_CONFIRMED` or `FAILED`.
   - The regex must match the whole message; `INCOMING_CONFIRMED` needs the named groups `payer` and `amount`.
   - `target: "styled"` matches against the colour-tagged text (e.g. `<green>Name</>`), which is a good anti-spoofing
     check if the real confirmation uses colours that players can't type in chat.
4. `/aucmod reload` and test with a small payment.

## Commands
| Command | What it does |
|---|---|
| `/aucmod create <duration> <start> <raise> [item name]` | Start an auction. Uses the item in your hand unless you give a name. e.g. `/aucmod create 10m 1k 100 Diamond Sword` |
| `/aucmod time set <d>` / `add <d>` / `sub <d>` | Change the time left at any point (`90`, `10m`, `1h30m`, max 7d) |
| `/aucmod status` | Item, highest bid, leader, time left, all bids |
| `/aucmod rules [n]` | Copies line n of the rules announcement to your clipboard |
| `/aucmod end` | End now |
| `/aucmod winner` | Shows the winner summary and copies the winner announcement (not sent) |
| `/aucmod close` | Save to history. Reminds you to deliver the item by hand |
| `/aucmod cancel` | Cancel and save to history |
| `/aucmod review` / `accept <id> <player> <amount>` / `reject <id>` | Handle flagged messages |
| `/aucmod history`, `/aucmod export json\|csv` | History and export (files in `config/donut-auction-manager/exports`) |
| `/aucmod capture on\|off`, `/aucmod reload` | Record messages / reload config |

Amounts accept `1500`, `1,500`, `2.5k`, `1.5m`, `3b`.

## How bids are judged
- Only server system/action-bar messages matching one of your verified formats count. Player chat is never parsed as a payment.
- Messages naming you as the payer are ignored. Duplicate events within 750 ms are dropped.
- A bid must be at least the starting bid, and at least the highest bid plus the minimum raise.
- Payments that arrive at or after the end time are marked LATE and never change the winner.
- Anything ambiguous is queued for you; it is never accepted automatically.
- The timer uses your computer's clock and the time your client received the message, so a payment right at the
  deadline may differ from the server's own timing.

## No refunds
The mod has no refund feature and never says a payment was refunded. The announcement tells bidders that all
payments are final (edit `terms` in `config.json`). Note that this means everyone who pays is out that money, including
people who get outbid. DonutSMP's published rules ban "external gambling" and a pay-to-bid auction where losers forfeit
their money can look like that, so ask DonutSMP staff whether this setup is allowed before you run it.

## Rules
Community-run wiki pages say the server bans unfair-advantage mods and macros, and says nothing explicit about
chat-reading or overlay mods. This mod only reads messages you already see and shows information. Still, check with
DonutSMP support that it is OK. Also check whether Lunar Client is allowed there.

## Build
1. Install JDK 21 and Gradle. Open https://fabricmc.net/develop , choose Minecraft 1.21.11, and copy the exact
   `yarn_mappings`, `loader_version` and `fabric_version` into `gradle.properties`.
2. In this folder run `gradle wrapper`, then `./gradlew build` (Windows: `gradlew.bat build`).
3. Your jar is `build/libs/donut-auction-manager-1.0.0.jar` (not the `-sources` one).

## Install on Lunar Client
Lunar loads Fabric mods on 1.16.5+: pick 1.21.11 in the launcher, open Mods (bottom-right), and drag the jar into the
window (the folder button opens the mods folder). The mod needs Fabric API; if Lunar reports it missing, add the
Fabric API 1.21.11 jar from Modrinth the same way. Not every mod works on Lunar; if it crashes, use plain Fabric.

## Files (config/donut-auction-manager/)
`config.json`, `current.json` (resumes after a restart), `history.json`, `capture.log`, `exports/`.

## Build without installing anything (GitHub)
1. Create a free GitHub repository and upload this whole folder (including the hidden `.github` folder).
2. Open the repository's Actions tab. The `build` workflow runs automatically on every push.
3. When it finishes, download the `donut-auction-manager-jar` artifact, unzip it, and use the jar that doesn't end in `-sources`.
If the build fails, open the failed step, copy the error text, and send it to whoever maintains the mod.
Before building, double-check `gradle.properties` against https://fabricmc.net/develop for Minecraft 1.21.11.
