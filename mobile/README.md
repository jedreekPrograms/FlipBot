# FlipBot Mobile

Expo / React Native companion for [FlipBot](../README.md). A WebView displays the existing dashboard; the backend, PostgreSQL and browser workers continue to run on the host computer.

**Status:** an Android APK build workflow is included. The Expo configuration also declares iOS, but this repository does not establish an equivalent released iOS build.

## Connection model

| Component | Responsibility |
| --- | --- |
| Phone app | Opens the configured dashboard address and remembers the server address |
| Spring Boot host | Serves the bundled dashboard and API on port `8081` |
| PostgreSQL and Playwright | Keep application state and execute automation on the host |
| Tailscale Serve | Provides private HTTPS access from devices in the same tailnet |

The host must remain powered on and connected. Away from the local network, the phone also needs an active Tailscale connection.

## Private remote access

Install Tailscale on the Windows host and phone and connect both to the same tailnet. From the FlipBot repository root, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\mobile\setup-remote-access.ps1
```

The setup script configures the existing backend through:

```powershell
tailscale serve --bg http://127.0.0.1:8081
tailscale serve status
```

Enter the displayed HTTPS `.ts.net` address in the app. This setup uses private Tailscale Serve access; it does not require router port forwarding or Tailscale Funnel.

For local-network use, the app also accepts a LAN address such as `http://192.168.1.37:8081`. That address is only useful while the phone can reach that network.

## Development

From `mobile/`:

```shell
npm install
npm start
```

The development server uses port `8082`. The package also provides `npm run android`, `npm run ios` and `npm run typecheck`. Development through Expo needs Metro; an installed standalone APK contains its own JavaScript bundle.

## Android build and dashboard packaging

- [Build FlipBot Mobile APK](../.github/workflows/build-mobile-apk.yml) type-checks the app, generates the Android project and builds a release APK.
- [Bundle mobile web panel](../.github/workflows/bundle-mobile-panel.yml) builds the React dashboard and copies it into the backend's static resources.

The checked-in push triggers target the mobile feature branch. Both workflows also expose manual dispatch; their availability is separate from whether a current artifact is retained.

A backend-bundled dashboard lets the phone use the host without a separate Vite development server.

## Updating an installed app

Pulling changes on the host does not replace the JavaScript bundle inside an installed APK. After app-code changes, rebuild and reinstall the APK or use the Expo development workflow.

The configuration version is `1.0.1`. Its connection handling rejects saved host values such as `undefined`, `null` and `nan`. If an old app still tries to load `http://undefined`, install an updated build; clearing its app data also resets the stored connection address.

Internal dashboard navigation stays in the app. External links, such as marketplace listing URLs, open in the system browser.
