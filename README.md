# ModDataPacks (mdp) - Forge 1.20.1

Server-side mod. Loads `.mdpfunc` scripts from `<minecraft root>/ModDataPacks/` (any subfolders).
Clients do not need the mod.

## Build / run
```
gradle wrapper --gradle-version 8.8     # once, to generate ./gradlew
./gradlew build                         # jar: build/libs/mdp-0.2.0.jar
./gradlew runServer                     # first run writes run/eula.txt -> set eula=true, run again
```
Test the parser, expressions and list files alone (no Minecraft needed):
```
mkdir -p /tmp/mdp-test
javac -d /tmp/mdp-test src/main/java/net/kodyris/mdp/script/*.java src/test/java/net/kodyris/mdp/script/*.java
java -cp /tmp/mdp-test net.kodyris.mdp.script.MdpParserTest
java -cp /tmp/mdp-test net.kodyris.mdp.script.MdpCondTest
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
- Events: `loaded()` (once the scripts are live: server start and after each `/mdp reload`), `tick`, `playerjoin(<player>)`, `teamjoin(<player>,<team>)`, `tagadded(<player>,<tagname>)`.
- `function Name(<args>)` is called with `/mdp run <file>:Name <args>` (file = path under ModDataPacks, no extension).
- `@perm=<0-4|any|op>` above a function; effective level = max(cfg.Permission, @perm). `op` follows server.properties.
- Variables: a player parameter (the first parameter of `playerjoin`/`teamjoin`/`tagadded`, or a function parameter named `player`) exposes `<player>` (the name, same as `<player.name>`), `<player.name>` and `<player.uuid>`. Use whatever name you gave the parameter, e.g. `<p.uuid>`. An unknown property like `<player.foo>` disables that block at load.
- `{}` on one line is an empty block, e.g. `evt loaded() {}`. Blocks with nothing in them are listed after `/mdp reload` as `Blocks with no data: N` (and in `/mdp status` and the log).
- A bad block disables only that block. `/mdp status` shows why.

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
- `else` must be on the same line as the closing brace: `} else {` / `} else if <cond> {`.
- Variables must be declared parameters (`<player>`, `<player.name>`, `<player.uuid>`, `<score>` ...). Unlike command lines, an unknown `<x>` in a condition is a load error.
- **Strings:** `==` `!=` against `"text"` (escape `\"` and `\\`). Case-sensitive.
- **Numbers:** `==` `!=` `<` `<=` `>` `>=`. A variable is text, so it takes the type of what it is compared with: `"5"` and `"5.0"` both equal `5`.
- **Bools:** `true` / `false`, `== true`, or a bare `<flag>` / `!<flag>` (the value must be `true` or `false`).
- **Lists:** `<x> exists in file.json`. The file is a JSON array (strings, numbers, bools) or an object (its keys). UUIDs match regardless of case. The path is relative to `ModDataPacks/`, must end in `.json`, and cannot leave that folder. Files are re-read when they change on disk, no `/mdp reload` needed. A missing file is listed after `/mdp reload` under `Missing list files`.
- **Logic:** `!` `&&` `||` and parentheses; `&&` binds tighter than `||`, and both short-circuit.
- `==` / `!=` never fail: a value of the wrong type just isn't equal. Ordering (`<` ...) needs numbers on both sides; if not, that condition is an error and every line it guards (the `if` side and the `else` side) is skipped. A function's caller is told; for events it is logged once per reload.

## Commands
- `/mdp run <file>:<Func> [args]` - anyone who meets the function's permission
- `/mdp reload`, `/mdp status` - level 2

See `run/ModDataPacks/example/welcome.mdpfunc`.
