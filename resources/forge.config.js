const path = require('path')
const fs = require('fs')

module.exports = {
  // electron-deeplink runs `node-gyp rebuild` unconditionally on every platform,
  // but its native binding is only loaded on macOS (pure-JS stub elsewhere). On
  // Windows without an MSVC toolchain that rebuild fails and blocks launch, so
  // skip it — no behavior change on Windows/Linux. See repo README.
  rebuildConfig: {
    ignoreModules: ['electron-deeplink']
  },
  packagerConfig: {
    name: 'Kip',
    icon: './icons/logseq_big_sur.icns',
    buildVersion: "92",
    appBundleId: "app.kip",
    // Kip's coop-maintenance scripts (electron.wiki spawns them via
    // ELECTRON_RUN_AS_NODE) ship at <app>/scripts, gulp-synced into
    // static/scripts with a pure-JS node_modules. `prune: false` keeps that
    // nested node_modules — the dependency-graph pruner would strip it.
    // (The 0.1 build was assembled by hand; see BUILD.md — electron-forge's
    // packager currently aborts silently on this machine's Node.)
    prune: false,
    protocols: [
      {
        "protocol": "kip",
        "name": "kip",
        "schemes": "kip"
      }
    ],
    // Kip doesn't build or sign for macOS. Left here (from upstream Logseq)
    // only so a future `electron-forge make` on macOS with your own
    // credentials in the env would work; unused otherwise.
    osxSign: process.env['APPLE_SIGN_IDENTITY']
      ? {
        identity: process.env['APPLE_SIGN_IDENTITY'],
        'hardened-runtime': true,
        entitlements: 'entitlements.plist',
        'entitlements-inherit': 'entitlements.plist',
        'signature-flags': 'library'
      }
      : undefined,
    osxNotarize: process.env['APPLE_ID']
      ? {
        tool: 'notarytool',
        appleId: process.env['APPLE_ID'],
        appleIdPassword: process.env['APPLE_ID_PASSWORD'],
        teamId: process.env['APPLE_TEAM_ID']
      }
      : undefined,
  },
  makers: [
    {
      'name': '@electron-forge/maker-squirrel',
      'config': {
        'name': 'Kip',
        'setupIcon': './icons/logseq.ico',
        'loadingGif': './icons/installing.gif',
        'certificateFile': process.env.CODE_SIGN_CERTIFICATE_FILE,
        'certificatePassword': process.env.CODE_SIGN_CERTIFICATE_PASSWORD,
        "rfc3161TimeStampServer": "http://timestamp.digicert.com"
      }
    },
    // WiX MSI maker removed for the 0.1 local build: its transitive native dep
    // (@bitdisaster/exe-icon-extractor) needs an MSVC toolchain to build here,
    // and `electron-forge package` (the runnable-folder target) never runs
    // makers anyway. To build an MSI later: `yarn add -D @electron-forge/maker-wix`
    // on a machine with the WiX Toolset + MSVC, and restore this block from git.
    {
      name: '@electron-forge/maker-dmg',
      config: {
        format: 'ULFO',
        icon: './icons/logseq_big_sur.icns',
        name: 'Kip'
      }
    },
    {
      name: '@electron-forge/maker-zip',
      platforms: ['darwin', 'linux', 'win32'],
    },

    {
      name: 'electron-forge-maker-appimage',
      platforms: ['linux'],
      config: {
        mimeType: ["x-scheme-handler/kip"]
      }
    }
  ],

  // Kip releases are cut by .github/workflows/build.yml (gh release), not
  // `electron-forge publish`. Repointed off logseq/og for correctness.
  publishers: [
    {
      name: '@electron-forge/publisher-github',
      config: {
        repository: {
          owner: 'JWE24-code',
          name: 'kip-app'
        },
        prerelease: true
      }
    }
  ]
}
