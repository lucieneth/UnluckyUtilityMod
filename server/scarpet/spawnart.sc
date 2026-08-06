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
      'slot <slot> <data>' -> 'give_at_slot'
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
