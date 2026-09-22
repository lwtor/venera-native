# Demo source verification

The demo source uses the same `explore` / `search.load` / `comic.loadInfo` / `comic.loadEp`
contract as production sources and only references generated repository fixtures.

1. Generate images: `java -Xmx2g tools/test-images/GenerateTestImages.java tools/test-images/out`.
2. Serve them: `python3 -m http.server 8765 --directory tools/test-images/out`.
3. For a USB device or emulator, run `adb reverse tcp:8765 tcp:8765`.
4. Open Sources, choose `tools/test-sources/demo_comic_source.js` with the system picker, and install it.
5. Verify Explore page 1 and 2, search, details, Chapter 1, all three network images, then leave and reopen the chapter to verify resume.

Stopping the HTTP server while a new page is requested must show the page retry state. This is the
offline check; detail data is intentionally local script data, so stopping the server does not make
the details call fail.
