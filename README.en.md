## malguem

[中文说明](readme.md)

An Android TV reader for epub comics and novels — every action works with just the remote control.

Four source types are supported: WebDAV / SMB / OPDS libraries (Komga, Kavita, Calibre-Web, …) / local storage.
When adding a source you can auto-scan the LAN for alist (port 5244) and SMB (port 445) servers, so you never have to type an IP address with a remote.

Epub files are parsed by streaming: only the byte ranges actually needed are fetched, so opening a book never downloads the whole file.

Because of the streaming design the app makes **very frequent** range requests. If your WebDAV server proxies a commercial cloud drive this may trigger its rate limiting — prefer a local disk or LAN NAS as storage.

### Remote control

- The top row of the home screen is **Recents** — click to continue reading
- On a list item, press **MENU or long-press OK**: book = switch comic/novel mode, source = edit/delete, recent = switch/remove
- While reading, press **OK** for the menu: table of contents / go to page / font size & dark mode (novel) / reading direction & single-page (comic) / switch mode
- The app checks for new releases at launch and installs updates automatically

#### Comic mode splits the screen into two pages side by side, shown whole without scrollbars. Turn pages with left/right; manga-style right-to-left order is available in the menu
![](comic_mode.webp)

#### Novel mode renders regular HTML: left/right switches chapters, up/down scrolls a full screen at a time.

![](novel_mode.webp)

![](盾牌格挡.webp)

This project is almost entirely written by AI — I have no frontend/Android/Java background, so please forgive bugs, thread-safety issues and style crimes.
