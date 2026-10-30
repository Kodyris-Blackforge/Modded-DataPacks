> [!NOTE]
> AI USAGE (Full generation)   Code is tested regularly to ensure valid/working

# ModDataPacks (mdp) - Forge 1.20.1

Server-side mod. Loads `.mdpfunc` scripts from `<minecraft root>/ModDataPacks/` (any subfolders).
Clients do not need the mod.

## Build / run
```
gradle wrapper --gradle-version 8.8     # once, to generate ./gradlew
./gradlew build                         # jar: build/libs/mdp-0.10.0.jar
./gradlew runServer                     # first run writes run/eula.txt -> set eula=true, run again
```
Test the parser, expressions and list files alone (no Minecraft needed):
```
mkdir -p /tmp/mdp-test
javac -d /tmp/mdp-test src/main/java/net/kodyris/mdp/script/*.java src/test/java/net/kodyris/mdp/script/*.java
java -cp /tmp/mdp-test net.kodyris.mdp.script.MdpParserTest
java -cp /tmp/mdp-test net.kodyris.mdp.script.MdpCondTest
java -cp /tmp/mdp-test net.kodyris.mdp.script.MdpInitTest
java -cp /tmp/mdp-test net.kodyris.mdp.script.MdpLocalTest
java -cp /tmp/mdp-test net.kodyris.mdp.script.MdpSayTest
```

## File format
```
//---MDP CONFIGS---
cfg.Enabled = true    //Configuration Enabled (Allows running)
cfg.Permission = 0    //Configuration Minimum Permission usability (0=any)
//---FUNC BELOW---

evt playerjoin(<player>) { ... }
@perm=op
function GiveKit(<player>) { ... }
```
- A file with no header gets the default one written to the top automatically on load (a blank file too). A header that exists but is wrong is never rewritten, it is reported as an error.
- Lines 1-4 are the header. L1/L4 must be empty or `//` comments. A bad header disables the whole file.
- Events: `init()` (once per world, per file; see below), `loaded()` (once the scripts are live: server start and after each `/mdp reload`), `tick`, `playerjoin(<player>)`, `teamjoin(<player>,<team>)`, `tagadded(<player>,<tagname>)`.
- `function Name(<args>)` is called with `/mdp run <file>:Name <args>` (file = path under ModDataPacks, no extension).
- `@perm=<0-4|any|op>` above a function; effective level = max(cfg.Permission, @perm). `op` follows server.properties.
- Variables: a player parameter (the first parameter of `playerjoin`/`teamjoin`/`tagadded`, or a function parameter named `player`) exposes `<player>` (the name, same as `<player.name>`), `<player.name>` and `<player.uuid>`. Use whatever name you gave the parameter, e.g. `<p.uuid>`. An unknown property like `<player.foo>` disables that block at load.
- `{}` on one line is an empty block, e.g. `evt loaded() {}`. Blocks with nothing in them are listed after `/mdp reload` as `Blocks with no data: N` (and in `/mdp status` and the log).
- Commands inside a block run with their feedback suppressed, like datapack functions (no "Set score..." spam in console or chat). `tellraw`, `say`, `title` etc. still show.
- A bad block disables only that block. `/mdp status` shows why.

## Init (`evt init`)
`evt init { ... }` runs **once per world**, the first time its file loads, and never again on its own. Use it for one-time setup (teams, scoreboards, storage defaults) that would be harmful to repeat on every `/mdp reload`. `evt loaded` is the every-load event, and `init` runs before it.
- Which files have run is recorded in `<world folder>/mdp_state.json`. It is saved *before* the handler runs, so a handler that reloads or crashes cannot run twice. If it cannot be saved, init is not run.
- `/mdp init run <file>` runs a file's init now (and records it as done). `/mdp init reset <file|*>` forgets it, so it runs again on the next load. `/mdp status` shows `init=done` or `init=pending`.
- A file's init runs only if the file is enabled and the `evt init` block is valid and not empty. Fix a broken one and reload, and it runs then.
- If `mdp_state.json` exists but is damaged, init is skipped (logged as an error) and the file is never overwritten.
- **Existing worlds:** the first load after you add an `evt init` will run it. To mark it as already done instead, create `<world folder>/mdp_state.json` before starting the server:
```
{"version":1,"initialized":["last_stand/core"]}
```

## Conditions
`if` / `else if` / `else` work inside any `function` or `evt` body, and nest:
```
function VipKit(<player>) {
if <player.uuid> exists in vip.json {
give <player> minecraft:diamond 1
} else if <player.name> == "Steve" {
say hi Steve
} else {
tellraw <player> {"text":"VIP only"}
}
}
```
- **One-line form:** `if <condition> { <command> }` on a single line holds exactly one command, for example `if count of players on team "Breacher" >= 4 { tellraw @a {"text":"Breacher is full"} }`. It has no `else`; for that, use the block form. Braces inside quoted strings, and balanced braces in the command (JSON / NBT), are fine.
- `else` must be on the same line as the closing brace: `} else {` / `} else if <cond> {`.
- Variables must be declared parameters (`<player>`, `<player.name>`, `<player.uuid>`, `<score>` ...). Unlike command lines, an unknown `<x>` in a condition is a load error.
- **Strings:** `==` `!=` against `"text"` (escape `\"` and `\\`). Case-sensitive.
- **Numbers:** `==` `!=` `<` `<=` `>` `>=`. A variable is text, so it takes the type of what it is compared with: `"5"` and `"5.0"` both equal `5`.
- **Bools:** `true` / `false`, `== true`, or a bare `<flag>` / `!<flag>` (the value must be `true` or `false`).
- **Lists:** `<x> exists in file.json`. The file is a JSON array (strings, numbers, bools) or an object (its keys). UUIDs match regardless of case. The path is relative to `ModDataPacks/`, must end in `.json`, and cannot leave that folder. Files are re-read when they change on disk, no `/mdp reload` needed. A missing file is listed after `/mdp reload` under `Missing list files`.
- **File exists:** `"vip.json" exists`, `<file> exists` (a variable or local holding the name) or a bare `vip.json exists` are true when that file **or folder** exists. The path is relative to `ModDataPacks/`, any extension is fine, and it follows the same safety rules as list files: no `..`, no absolute paths, nothing that leaves `ModDataPacks` (a symlink that points outside is an error). A literal path that breaks the rules disables the block at load; a bad name in a variable is a condition error at run time, and the lines it guards are skipped. It is checked live each time, and a missing file is not reported on reload (being absent is the point). Combine as usual: `!("lock.txt" exists)`, `"vip.json" exists && <player> has tag "Default"`.
- **Tags:** `<player> has tag "Queued"` (the tag can also be a variable: `has tag <tagname>`). The left side must be a player parameter. Combine freely: `<player> has tag "Default" && <player> has tag "Queued"`, or `!(<player> has tag "X")` for "does not have". The tag is read from the live player when the call starts. If the player is offline the condition is an error and the lines it guards are skipped. There is no function that returns a list of tags, on purpose: a tag name pasted into a command line could carry quotes or other characters that break it, whereas `has tag` only ever answers yes or no.
- **Teams:** `<player> has team "Breacher"` (the team can also be a variable: `has team <team>`). Same rules as `has tag`: the left side must be a player parameter, the value is read from the live player when the call starts, and an offline player is an error. A player on no team simply doesn't match any team, so use `!(<player> has team "X")` for "not on X". Note that in `evt teamjoin(<p>,<team>)` the team that was just joined is already in `<team>`, so `<team> == "Breacher"` is the way to test it there.
- **Counts:** `count of players tagged "Breacher"` and `count of players on team "Breacher"` are numbers, so they work on either side of `==` `!=` `<` `<=` `>` `>=` against a number or a variable: `if count of all players tagged "Breacher" > <limit> {`. `all` is optional, `in team` works as well as `on team`, and the tag or team can be a quoted string, a bare word or a variable. They count every online player (spectators included), read live when the call starts. Need no player parameter, so they work in `evt tick` too. A count on its own isn't a condition; compare it with something. There is no arithmetic (`count - 1`), so compare against the adjusted number instead.
- **When conditions are decided:** every condition in a block is evaluated once, when the block starts, before any of its commands run. A command earlier in the same block that adds or removes a tag does not change a later `if`. Anything that depends on a change made mid-block needs a second block, or a plain `execute if entity @a[name=...,tag=...]` command.
- **Logic:** `!` `&&` `||` and parentheses; `&&` binds tighter than `||`, and both short-circuit.
- `==` / `!=` never fail: a value of the wrong type just isn't equal. Ordering (`<` ...) needs numbers on both sides; if not, that condition is an error and every line it guards (the `if` side and the `else` side) is skipped. A function's caller is told; for events it is logged once per reload.

## Messages (`say`)
```
say "Breacher is full"                            // plain text, to the block's player
say "Welcome, <player.name>" to <player>
say "Server is restarting" to everyone            // also: to all
say {text:"Breacher is full",MCCOL:gold}          // coloured, with a named colour
say {text:"Breacher is full",hex:ffaa00}          // coloured, with a hex colour
say {text:"Breacher is full",color:gold}          // ...or write the vanilla field yourself
say {text:"Breacher is full",MCCOL:gold} to everyone
```
`say` sends a chat message and compiles to `tellraw <target> <component>`. With no `to`, it goes to the block's player (the first player parameter: `<player>` in a function, the player of `playerjoin` / `teamjoin` / `tagadded`). A block with no player (`tick`, `loaded`, `init`) must say `to everyone`, or `to <p>` for a player parameter.
- **Colours:** inside `{...}`, `MCCOL:<colour>` takes one of the 16 Minecraft colours (`black dark_blue dark_green dark_aqua dark_red dark_purple gold gray dark_gray blue green aqua red light_purple yellow white`) and `hex:<rrggbb>` takes 6 hex digits (a leading `#` is fine). Both are rewritten to the real `color` field before the command is checked, work in nested components such as `extra:[...]`, and are case-insensitive. Text inside strings is never rewritten. A bad colour or hex value disables just that block, with the line number. Fields are separated by commas: `{text:"x":hex:ffaa00}` is an error that says so.
- **The component** is checked at load by Minecraft's own text parser, so a typo in any other field (for example an unknown vanilla colour) also disables only that block. Use `["a",{...}]`-style lists by writing `{text:"",extra:[...]}`.
- `<variables>` and locals can be used in the text and are filled in with their value at that line. Their values are escaped, so a quote or backslash in a value can't break the message.
- Escape a quote inside plain text as `\"` and a backslash as `\\`.
- It is only this effect when the message starts with a double quote or a `{`. `say hello` and `say [VIP] hello` are still the ordinary Minecraft `say` command (which broadcasts as `[Server] hello`), and `say` inside `execute ... run say ...` is untouched.
- Because it becomes `tellraw`, a function that uses `say` and is called with `/mdp run` needs the caller to have permission level 2. Events (which run at level 4) and ops are unaffected.

## Local variables
```
function Check(<player>) {
local IsFilled = false
if count of players on team "Breacher" >= 4 {
IsFilled = true
tellraw <player> {"text":"Breacher is full"}
}
if <IsFilled> {
say filled
}
}
```
- `local Name = value` declares a variable, `Name = value` changes it (spaces around `=` are optional). Read it as `<Name>`, in conditions and in command lines.
- The value can be a string, number or `true`/`false`, another variable (`<player.name>`), a count (`count of players tagged "X"`), or a whole condition (`local full = count of players on team "Breacher" >= 4` stores `true` or `false`). There is no arithmetic.
- Declare locals at the **top level** of the block (not inside an `if`), before they are used. Assign to them anywhere after, including inside `if` / `else` and one-line ifs (`if <cond> { IsFilled = true }`).
- A local belongs to one block and one call: each call starts fresh, other blocks can't see it, and it can't have the same name as a parameter. Locals have no properties (`<x.name>` is an error).
- **Order:** the whole block's logic runs once, in order, when the block starts, then the resulting commands run. Each command gets the local's value *at its own position*, so `say <n>` before and after `n = 2` print different numbers. An `else` is decided by the `if` condition's value when it was first evaluated, so changing a local inside the `if` never flips the `else`. Because the commands run afterwards, a command can't change a local; only a later assignment can.
- A value that can't be worked out (for example a comparison of text with a number) is reported, the local is left unset, and anything that depends on it is skipped.

## Commands
- `/mdp run <file>:<Func> [args]` - anyone who meets the function's permission
- `/mdp reload`, `/mdp status`, `/mdp init run|reset <file>` - level 2

See `run/ModDataPacks/example/welcome.mdpfunc`.
