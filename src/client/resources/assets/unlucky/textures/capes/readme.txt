Custom capes for the Capes module
=================================

The official Mojang capes are NOT bundled here. They stream on demand from
Mojang's own texture server (the same source vanilla uses) and are cached under
your client config dir (config/unlucky/capes/mojang/), so nothing copyrighted is
redistributed and there's no download step. They show up under the module's
"mojang" folder dropdown automatically.

This folder is only for YOUR OWN capes
---------------------------------------
  Drop your own cape PNGs in here (or in a subfolder) and they're auto-discovered:
    capes/my_cape.png        -> Folder "custom", cape "My cape"
    capes/mypack/red.png     -> Folder "mypack", cape "Red"
  ("mojang" is reserved for the streamed official set.)

Format
------
  Standard 64 x 32 cape texture, PNG, lowercase filename (underscores for spaces).
  The same texture is reused for the elytra, matching official capes 1:1.

Notes
-----
  * Only your own cape is affected, and rendering uses the vanilla cape/elytra
    layers (via a getSkin swap), so physics/positioning always match the original.
  * "Hide real cape" removes your cape entirely.
  * Streamed capes need network on first use, then work offline from the cache.
