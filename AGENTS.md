# Flutter client

All client sources and deliverable artifacts stay in this directory. Shared art
lives in ../asset-library under immutable content-hash snapshots, explicitly
requested by the user. resource-library.lock.json pins the snapshot; generated
assets/game/assets and fonts are symlinks. Never edit or delete shared snapshots
from the export script. Read server files only; do not replace server assets with
library links. Disposable
build caches may use /tmp to avoid slow shared-filesystem metadata operations.
The user permits changes
to non-production containers when necessary; never change or redeploy production.
Prefer client-only fixes when the server is healthy. Read game files only from
../spa-test/src when exporting resources. Never include server configuration,
credentials, accounts, databases, or private Node modules in a client bundle.

Android is the first supported build. Windows is a follow-up target. Preserve the
remote HTTPS origin and existing authentication. Serve packaged art only when a
server URL's content hash matches the packaged manifest. Never bypass TLS errors.

Exported game art is ignored, following the source project's no-art-in-git rule.
