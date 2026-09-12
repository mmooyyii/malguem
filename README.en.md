## malguem

[中文说明](readme.md)

An Android TV reader for epub and cbz comics and novels — every action works with just the remote control.

Four source types are supported: WebDAV / SMB / OPDS libraries (Komga, Kavita, Calibre-Web, …) / local storage.
When adding a source you can auto-scan the LAN for alist (port 5244) and SMB (port 445) servers, so you never have to type an IP address with a remote.

### Streaming: the whole file is never downloaded

Both epub and cbz are zip underneath. This app never downloads a book before opening it — it uses HTTP Range to fetch only the bytes it is about to put on screen:

- **Opening a book: usually one round trip.** It reads the last 512KB of the file, which normally contains both the EOCD and the entire central directory, so every entry's offset and length is known at once. For epub it then parses the opf for spine order, table of contents and cover.
- **Opening the same book again: zero round trips.** The parsed central directory and opf are stored in SQLite, so the next time the book opens straight from the local database and the very first request is the content itself. The cover row on the home screen works the same way.
- **One request per screen, not per file.** The gap between adjacent entry offsets is the exact length of that entry's [local header + compressed data], so a page and every image/CSS/font it references are merged into a single multi-range request and arrive in one round trip — without the usual second trip to read the local header first.
- **Prefetch ahead.** 16 pages ahead for comics, 5 chapters ahead for novels. Turning a page drops any queued stale prefetch so the current page gets the bandwidth. On high-latency links a large batch of ranges is split across 4 parallel connections.
- **Fetched content goes into an LRU cache** capped at the smaller of 256MB and half the heap, so an all-night session won't blow up a low-memory box.

Opening a 500MB comic therefore costs the 512KB tail of the file; each page's image is fetched only when you turn to it.

The trade-off is request volume. The app makes **very frequent** range requests, so if your WebDAV server proxies a commercial cloud drive this may trigger its rate limiting — prefer a local disk or LAN NAS as storage.

### Remote control

- The top row of the home screen is **Recents** — click to continue reading
- On a list item, press **MENU or long-press OK**: book = switch comic/novel mode, source = edit/delete, recent = switch/remove
- While reading, press **OK** for the menu: table of contents / go to page / font size & dark mode (novel) / reading direction & single-page (comic) / switch mode
- A **Check updates** tile on the home screen downloads and installs new releases (no implicit background checks);
  long-press it to set a custom update source (any http folder holding the apk + version.json, e.g. your alist; `http://user:pass@host/path/` is supported)

#### Comic mode splits the screen into two pages side by side, shown whole without scrollbars. Turn pages with left/right; manga-style right-to-left order is available in the menu

A cbz is just a zip full of images — one image per page, ordered by natural filename sort (page2 comes before page10), fetched on demand like everything else. Since there is no text flow, cbz files are comic-mode only.
![](comic_mode.webp)

#### Novel mode renders regular HTML: left/right switches chapters, up/down scrolls a full screen at a time.

![](novel_mode.webp)
