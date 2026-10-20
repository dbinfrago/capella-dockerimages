<!--
 ~ SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
 ~ SPDX-License-Identifier: Apache-2.0
 -->

# Papyrus diagram cache

The Papyrus diagram cache exports all diagrams and tables of a Papyrus model
as SVG files. It uses the [Papyrus base image](../../papyrus/base.md) (Papyrus
7.x) and produces the same layout as the Capella diagram cache: one
`<uuid>.svg` per diagram, an `index.json` and an `index.html`.

- GMF diagrams (class, state machine, sequence, ... diagrams) and Sirius
  representations of an `.aird` next to the `.di` file are rendered by Papyrus.
- Papyrus tables are rendered from their definition in the `.notation` file
  (visible columns and rows, column widths).

GMF diagrams are exported reproducibly: an unchanged diagram yields a
byte-identical SVG, so tools that compare SVGs (e.g. to sync them to Polarion)
only see diagrams whose content changed.

`index.json` is a list of entries with the keys `uuid`, `format`, `name`,
`type`, `viewpoint` (top-level package of the owner), `success`, `kind`
(`gmf`, `table` or `sirius`) and `owner` (`xmi:id` of the owning model
element).

Please add the following section to your `.gitlab-ci.yml`:

```yaml
variables:
  PAPYRUS_VERSION: 7.1.0 # Enter the Papyrus version of the model here

include:
  - remote: https://raw.githubusercontent.com/dbinfrago/capella-dockerimages/${CAPELLA_DOCKER_IMAGES_REVISION}/ci-templates/gitlab/papyrus-diagram-cache.yml

update_papyrus_diagram_cache:
  variables:
    ENTRYPOINT: model/model.di # Entry point to the .di file of the model (relative from root level of the repository)
```

The `.di` file or one of its parent directories must contain the Eclipse
`.project` file of the model.

In addition, you have to add the following environment variables on repository
level. Make sure to enable the "Expand variable reference" flag.

- `CAPELLA_DOCKER_IMAGES_REVISION`: Revision of this Github repository
- `DOCKER_REGISTRY`: Registry that contains the `papyrus/base` image, e.g.
  `ghcr.io/dbinfrago/capella-dockerimages` for the prebuilt image

Set `PUSH_DIAGRAM_CACHE: "true"` to push the cache to the orphan branch
`diagram-cache/<branch>`. The job exits with an error if one or more diagrams
could not be exported.

This is the minimal configuration. For more advanced configuration options,
please refer to the
[Gitlab CI template](https://github.com/dbinfrago/capella-dockerimages/blob/main/ci-templates/gitlab/papyrus-diagram-cache.yml).
