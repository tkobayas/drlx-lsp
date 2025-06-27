### Developer notes
**Precompiled-server - no debug**
1. package server side code with `mvn clean package`
2. goto `client` directory
3. issue `npm install`
4. issue `code .` to start VSCode in that directory
5. inside VSCode, select `Run and Debug` (Ctrl+Shift+D) and then start `Run Extension`
6. a new `Extension Development Host` window will appear, with `drl` extension enabled
7. to "debug" server-side event, add `server.getClient().showMessage(new MessageParams(MessageType.Info, {text}));` in server-side code

**Connected remote server - debug**
1. package server side code with `mvn clean package`
2. start server with `DrlxsLspTCPLauncher` from IDE on debug mode; this will start the LSP-server listening on port `9925`
3. goto `client` directory
4. issue `npm install`
5. issue `code .` to start VSCode in that directory
6. inside VSCode, select `Run and Debug` (Ctrl+Shift+D) and then start `Debug Extension`
7. the extensions will establish a connection to the server running at port `9925`
8. a new `Extension Development Host` window will appear, with `drl` extension enabled
9. to "debug" server-side event, add breakpoints in server-side code