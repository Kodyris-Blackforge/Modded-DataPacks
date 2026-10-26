> [!NOTE]
> AI USAGE!  AI output is tested to confirm stability.
# Modded-DataPacks
Modded Datapacks increases the usability of server-based datapacks, by granting better qualities, usage, easier creation of functions, and more.

Features:
* evt tick {} -> Behaves as if it was a ticked event inside of a mcfunction
* evt playerjoin(<player>) {} -> allows you to add events to players on join.
* evt init {} -> Runs first time per-world, can be reset
* evt tagadded(<player>,<tag>) {} -> allows for running commands upon a tag being added to a player
* function <funcname>(vars) {} -> allows for creating functions without needing to create a new file

Commands:
* `/mdp init run <file>` -> runs a init file immediately, marks it as done
* `/mdp init reset <file|*>` -> runs a file on next `/mdp reload`
* `/mdp status` -> shows file status, errors, and more
* `/mdp reload` -> Reloads all files, and shows init runs
