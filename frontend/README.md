# FlipBot Dashboard

React and TypeScript dashboard for [FlipBot](../README.md). It provides the operator interface for bot configuration, listing history, negotiation settings, market statistics and runtime monitoring.

**Status:** part of the actively developed FlipBot application. The [root README](../README.md) contains the application demo, architecture and backend setup requirements.

## Local development

Requirements: Node.js 22+, npm, and a configured FlipBot backend listening on `localhost:8081`.

```shell
npm ci
npm run dev
```

Run these commands from `frontend/`. Vite proxies `/api` requests to `http://localhost:8081` as configured in [vite.config.ts](vite.config.ts).

## Checks and production build

```shell
npm run lint
npm run build
```

The build runs TypeScript compilation followed by Vite and writes static output to `dist/`. `npm run preview` serves the built frontend locally; it is not a replacement for the backend or a production deployment configuration.

## Relationship to the mobile app

[FlipBot Mobile](../mobile/README.md) displays the same dashboard through a WebView. Building this module alone does not automatically update a dashboard bundle already packaged into the backend.

See [package.json](package.json) for the actual scripts and dependencies and [CI](../.github/workflows/ci.yml) for automated validation.
