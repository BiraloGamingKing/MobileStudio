# Mobile Studio

Edit/Publish Roblox games from your mobile device.
<p align="center">
  <img src="https://github.com/user-attachments/assets/0203df09-abc7-4cc3-9ba6-e6c28b066da5" width="48%" />
  <img src="https://github.com/user-attachments/assets/b1179fe0-5fc8-4210-a5e8-b4f0c89a0b52" width="48%" />
  <img src="https://github.com/user-attachments/assets/e6dda4c8-be36-4acc-ae28-f72198e4a4e8" width="48%" />
</p>
You are able to do almost anything that the real desktop computer studio can do, but instead, all from your mobile device. There are still many issues and missing things, and many more things that need to be done. But for now, you can do most things. This works offline to.

> Not affiliated with, endorsed by, or sponsored by Roblox Corporation.

- **Download:** [latest builds on MediaFire](https://www.mediafire.com/folder/33xwlczg9fxs1/mobile+studio)
- **Videos:** [youtube.com/@fiowwwys](https://www.youtube.com/@fiowwwys/featured)

## Features

- Open and save `.rbxl` / `.rbxm` places and models; Explorer, Properties, Toolbox (Creator Store) and Output panels you can dock or float
- Build with move / scale / rotate tools, unions, terrain (generate biomes, sculpt, paint) and lighting effects
- Luau scripting with a command bar, autocomplete and a sandboxed runtime; playtest with R6/R15 characters, animations, emotes, teams and spawns
- Runs many Studio plugins (F3X, Archimedes and others)
- Sign in to Roblox to open your games, use your avatar, and publish (Open Cloud)
- **AI Assistant:** chat with Claude to build in your place, using your own Anthropic API key, plus an MCP server so other AI apps can connect

See the in-app **Update Log** (`app/src/main/java/.../ui/UpdateLog.kt`) for the full list.

## FAQ

### How did you make this?
I created this with Android Studio and Claude Code, which did require computer. Would you rather have waited way longer for someone to create ALL of this, or use it now? This might have taken months years to get this far if no AI was used. https://x.com/ashen_one/status/2123230920626884849

### Is this safe?
Ya. The full source code is right here in this repo, so anyone can check what it does.

### Will I get banned for using this?
I doubt it, it's using something similar to the Roblox game Studio Lite to publish/save/edit your games. I've been using on my main account for over a week now and no issues. I've been creating, publishing, saving, editing.

### iOS support wen?
I don't really use iOS anymore, but I will try to make an iOS version soon. I have heard that someone used an Android emulator to install it on their iOS device, so you could probably do that for now.

If you still don't feel comfortable using it, that's understandable, but you can always use a guest account or make a brand new account for this if you want to use it.

Anyways, enjoy❤

## Building

Requirements:

- A recent Android Studio (the project uses Android Gradle Plugin 9.5 and compile SDK 37)
- JDK 25 (Gradle downloads it automatically through the toolchain settings if you don't have it)

Open the folder in Android Studio and press **Run**, or from a terminal:

```sh
./gradlew :app:assembleDebug        # macOS / Linux
gradlew.bat :app:assembleDebug      # Windows
```

Android Studio creates `local.properties` (your SDK path) on first open. It's machine-specific and
ignored by git.

### Tests

`gradlew :app:testDebugUnitTest` runs the JVM tests. Tests that need real Roblox files (meshes,
places, plugins) are skipped unless you point their environment variable at your own samples,
for example `RBX_SAMPLES`, `MESH_SAMPLES` or `PLUGIN_DUMP`. Each test file says which one it reads.

## Material textures (not included)

Roblox's material textures and studs texture aren't in this repository, because they belong to
Roblox. Without them the app draws its own procedural materials and studs.

For a **personal** build you can add copies from your own Roblox Studio install to
`app/src/main/assets/materials/`:

```
materials/modern/<material>_c.jpg     color map   (e.g. brick_c.jpg)
materials/modern/<material>_n.jpg     normal map  (e.g. brick_n.jpg; plastic only needs plastic_n.jpg)
materials/pre2022/<material>_c.jpg / _n.jpg
materials/surfaces.png                R = studs, G = weld/glue, B = inlet
```

Material names are listed in `render/MaterialTextures.kt`. This folder is git-ignored. Don't
publish APKs built with these files.

## Privacy

- Your Roblox sign-in is stored only on the device and sent only to Roblox. It's excluded from
  Android backups.
- Your Anthropic API key (AI Assistant) is stored only on the device and sent only to Anthropic.
- Scripts and plugins run in a sandbox; HTTP requests from them ask before contacting a new site.

## License

The source code is released under the [MIT License](LICENSE). The bundled fonts have their own
licenses (SIL OFL 1.1, Apache 2.0 and the Ubuntu Font Licence); see
[licenses/fonts](licenses/fonts/README.md).

"Roblox" and "Roblox Studio" are trademarks of Roblox Corporation.
