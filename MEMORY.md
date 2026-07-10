Memory index
Architecture doc — repo-root ARCHITECTURE.md is the canonical mixin/feature map + 26.2 API traps; read first, update on every version bump
Two-number versioning — versions are major.minor (1.0), never 1.0.0; sync gradle.properties + UnluckyClient.VERSION
Unlucky Client project — visuals-first MC 26.2 Fabric utility client; GuiGraphicsExtractor API notes and mixin hook points
SectionCompiler threading — chunk compile runs on worker threads; snapshot render state on main thread, avoid FRAPI redirect clashes
Chams render pipeline — 26.2 deferred entity rendering: stash on render state in extract, re-submit model in submit; custom no-depth RenderPipeline for through-walls
Cape skin override — 26.2 custom capes via AbstractClientPlayer.getSkin swap; PlayerSkin/ClientAsset.ResourceTexture API, AvatarRenderState rename, cape=elytra for 1:1