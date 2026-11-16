# SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
# SPDX-License-Identifier: Apache-2.0
"""Dropins registered in ``<product>/versions/<version>/dropins.yml``."""

import logging
import typing as t

import typer
import yaml

from cdi import _repository_root, formatting

log = logging.getLogger(__name__)


def load_dropins_files(product: str, version: str) -> dict[str, t.Any]:
    """Load the dropins files from the product version directory."""

    base_dir = _repository_root / product / "versions" / version
    dropins = yaml.safe_load(
        (base_dir / "dropins.yml").read_text(encoding="utf-8")
    )["dropins"]

    dropins_overwrites = {}
    dropins_overwrites_path = base_dir / "dropins.overwrites.yml"
    if dropins_overwrites_path.exists():
        dropins_overwrites_content = yaml.safe_load(
            dropins_overwrites_path.read_text(encoding="utf-8")
        )
        if "dropins" in dropins_overwrites_content:
            dropins_overwrites = dropins_overwrites_content["dropins"]

    return dropins | dropins_overwrites


def validate_dropins(product: str, version: str, dropins: list[str]) -> None:
    """Make sure that the list of dropins is available for the product version."""
    available_dropins = load_dropins_files(product, version)

    for dropin in dropins:
        if dropin and dropin not in available_dropins:
            log.error(
                "Dropin '%s' not found in the list of supported dropins for %s version %s."
                " There are multiple options to resolve the issue:\n"
                "1. If the dropin is missing in the %s"
                " and you think it should be there, please open a new %s\n"
                "2. Download the dropins and add them to the '%s/versions/%s/dropins' directory.\n"
                "3. Create a file '%s/versions/%s/dropins.overwrites.yml' and add the required dropins.",
                dropin,
                product.capitalize(),
                version,
                formatting.build_rich_link(
                    link=f"https://github.com/dbinfrago/capella-dockerimages/blob/main/{product}/versions/{version}/dropins.yml",
                    text="list of dropins",
                ),
                formatting.build_rich_link(
                    link="https://github.com/dbinfrago/capella-dockerimages/issues",
                    text="GitHub issue",
                ),
                product,
                version,
                product,
                version,
            )
            raise typer.Exit(1)
