//
// spawnart.sc — creative-hotbar restore for a survival anarchy server.
//
// Drop this in <world>/scripts/ on a Carpet server. It autoloads on start
// (Carpet's `scriptsAutoload` rule is true by default), so there is nothing
// to install into the server jar and nothing to keep in sync across updates.
//
// WHAT IT DOES
//   Rebuilds an item from a serialised ItemStack and puts it in your inventory.
//   The payload is whatever `ItemStack.CODEC` produced on the client, so a stack
//   round-trips exactly: every data component, no re-derivation, no guessing.
//
// WHY IT WORKS WITHOUT OP
//   Vanilla only accepts a client-authored ItemStack through
//   ServerboundSetCreativeModeSlotPacket, which is gated on abilities.instabuild
//   and unreachable from any packet. Scarpet's inventory_set() writes to the
//   container directly and never goes near that handler. `command_permission`
//   below decides who may ask; the app's own authority does the write.
//
//   'all' means EVERY player on the server can spawn arbitrary items. That is
//   the point on a private anarchy server and a catastrophe on a public one.
//   Change it to 'ops' to keep this to yourself.
//
// USAGE
//   /spawnart give <snbt>          put the stack in the first free slot
//   /spawnart slot <n> <snbt>      put it in a specific slot (0-8 = hotbar)
//   /spawnart ping                 check the app is loaded
//
//   For payloads over the 32767-character command limit:
//   /spawnart begin                start a chunked transfer
//   /spawnart chunk <part>         append one piece (repeat)
//   /spawnart commit give          reassemble and place
//   /spawnart commit slot <n>      reassemble and place at a slot
//
// WHY CHUNKING IS NEEDED
//   ServerboundChatCommandPacket writes the command with a bare writeUtf(), so
//   32767 characters is a hard ceiling — and exceeding it is an EncoderException
//   on the netty thread, which drops the connection rather than failing the
//   command. A saved shulker full of shulkers serialises to megabytes, so the
//   client sends it in pieces and this rebuilds it here.
//
//   The pieces find each other because global_ variables persist between calls.
//   'scope' -> 'player' below means each player accumulates into their own
//   buffer, so two people transferring at once do not interleave.
//
// The client half (Unlucky's HotbarLoadout module) generates the SNBT from
// your local hotbar.nbt and sends these commands for you.
//

__config() -> {
   'scope' -> 'player',
   'stay_loaded' -> true,
   'command_permission' -> 'all',
   'commands' -> {
      'ping' -> 'ping',
      'give <data>' -> 'give_first_free',
      'slot <slot> <data>' -> 'give_at_slot',
      'begin' -> 'begin',
      'chunk <data>' -> 'chunk',
      'commit give' -> 'commit_first_free',
      'commit slot <slot>' -> 'commit_at_slot'
   },
   'arguments' -> {
      'data' -> { 'type' -> 'text' },
      'slot' -> { 'type' -> 'int', 'min' -> 0, 'max' -> 40 }
   }
};

ping() -> print(player(), 'spawnart loaded');

// inventory_set(inventory, slot, count, item, nbt): when nbt is present the
// whole stack is rebuilt from it via ItemStack.CODEC, so the item name is
// ignored and the count rides along inside the payload. Passing null for count
// is what keeps that stored count instead of overriding it.
_place(slot, data) -> (
   p = player();
   try(
      inventory_set(p, slot, null, 'stone', data);
      print(p, str('spawnart -> slot %d', slot));
      true;
   ,
      print(p, str('spawnart failed: %s', _trace));
      false;
   )
);

give_first_free(data) -> (
   p = player();
   // inventory_find with a null item returns the first EMPTY slot
   slot = inventory_find(p, null);
   if (slot == null,
      print(p, 'spawnart: inventory full');
      return(false)
   );
   _place(slot, data)
);

give_at_slot(slot, data) -> _place(slot, data);

//
// Chunked transfer.
//
// global_parts is a list, not a string. Appending with `s = s + part` would copy
// the whole accumulated payload on every chunk, and a barrel of barrels arrives
// in well over a hundred of them — quadratic in the size that already made this
// necessary. put(list, null, x) appends a reference and join() walks it once.
//
global_parts = [];

begin() -> (
   global_parts = [];
   print(player(), 'spawnart: receiving');
   true
);

chunk(data) -> (
   put(global_parts, null, data);
   // silent by design: one reply per chunk is a hundred lines of chat for one item
   true
);

// Joins, places, and drops the buffer either way — a failed transfer must not
// leave megabytes parked in the app host waiting for the next one.
_commit(slot) -> (
   if (length(global_parts) == 0,
      print(player(), 'spawnart: nothing buffered — send /spawnart begin first');
      return(false)
   );
   data = join('', global_parts);
   global_parts = [];
   if (slot == null,
      p = player();
      free = inventory_find(p, null);
      if (free == null,
         print(p, 'spawnart: inventory full');
         return(false)
      );
      _place(free, data)
   ,
      _place(slot, data)
   )
);

commit_first_free() -> _commit(null);
commit_at_slot(slot) -> _commit(slot);
