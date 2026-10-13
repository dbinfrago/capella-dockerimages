#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
# SPDX-License-Identifier: Apache-2.0
"""Export all diagrams and tables of a Papyrus model into a diagram cache.

The cache layout follows ``capellambse.diagram_cache``: one ``<uuid>.svg``
per diagram plus, with ``--index``, an ``index.json`` and ``index.html``.

GMF diagrams and Sirius representations are rendered by the headless
``org.dbinfrago.papyrus.diagramcache.export`` Papyrus application. Papyrus
tables (NatTable) cannot be rendered headless and are drawn here from the
table definition in the ``.notation`` file.
"""

from __future__ import annotations

import argparse
import dataclasses
import html
import json
import os
import datetime
import pathlib
import shutil
import subprocess
import sys
import tempfile
import typing as t
import xml.etree.ElementTree as ET

UML_XMI = "http://www.omg.org/spec/XMI/20131001"
NOTATION_XMI = "http://www.omg.org/XMI"
XSI = "http://www.w3.org/2001/XMLSchema-instance"
UML_NS = "http://www.eclipse.org/uml2/5.0.0/UML"
UML_FEATURE_PREFIX = UML_NS + "#//"
STEREOTYPE_FEATURE_PREFIX = "property_of_stereotype:/"
EXPORT_APPLICATION = "org.dbinfrago.papyrus.diagramcache.export"

COLUMN_WIDTH = 176
ROW_HEIGHT = 22
HEADER_FILL = "#e6e6e6"
BODY_FILL = "#ffffff"
STROKE = "#9a9a9a"
FONT = 'font-family="Arial, sans-serif" font-size="12"'


@dataclasses.dataclass
class Entry:
    uuid: str
    name: str
    type: str
    kind: str
    owner: str | None
    success: bool


@dataclasses.dataclass
class Column:
    label: str
    width: int
    feature: str
    stereotype: str | None = None


class Model:
    """Minimal read-only view of a Papyrus ``.uml`` file."""

    def __init__(self, path: pathlib.Path) -> None:
        root = ET.parse(path).getroot()
        self.elements: dict[str, ET.Element] = {}
        self.parents: dict[str, str] = {}
        self.stereotypes: dict[tuple[str, str], ET.Element] = {}
        self.model: ET.Element | None = None
        for child in root:
            if _xmi_type(child, UML_XMI) == "uml:Model" or (
                child.tag == f"{{{UML_NS}}}Model"
            ):
                self.model = child
                self._index(child, None)
            else:
                stereotype = child.tag.rsplit("}", 1)[-1]
                for key, value in child.attrib.items():
                    if key.startswith("base_"):
                        self.stereotypes[(value, stereotype)] = child
        if self.model is None and root.tag == f"{{{UML_NS}}}Model":
            self.model = root
            self._index(root, None)
        if self.model is None:
            raise ValueError(f"No uml:Model found in {path}")

    def _index(self, element: ET.Element, parent: str | None) -> None:
        xmi_id = element.get(f"{{{UML_XMI}}}id")
        if xmi_id is not None:
            self.elements[xmi_id] = element
            if parent is not None:
                self.parents[xmi_id] = parent
            parent = xmi_id
        for child in element:
            self._index(child, parent)

    @property
    def name(self) -> str:
        assert self.model is not None
        return self.model.get("name", "")

    def viewpoint(self, owner: str | None) -> str:
        """Return the name of the top-level package containing *owner*."""
        assert self.model is not None
        model_id = self.model.get(f"{{{UML_XMI}}}id")
        current = owner
        top: str | None = None
        while current is not None and current != model_id:
            top = current
            current = self.parents.get(current)
        if top is None or current != model_id:
            return self.name
        element = self.elements[top]
        if _xmi_type(element, UML_XMI) != "uml:Package":
            return self.name
        return element.get("name", self.name)

    def label(self, value: str) -> str:
        """Resolve space separated element references to names."""
        parts = value.split()
        if parts and all(p in self.elements for p in parts):
            return ", ".join(self.elements[p].get("name", p) for p in parts)
        return value


def _xmi_type(element: ET.Element, xmi_ns: str) -> str | None:
    return element.get(f"{{{xmi_ns}}}type")


def _fragment(href: str | None) -> str | None:
    if not href or "#" not in href:
        return None
    return href.split("#", 1)[1]


def find_project(model: pathlib.Path) -> pathlib.Path | None:
    """Return the nearest directory at or above *model* with a ``.project``."""
    for directory in model.parents:
        if (directory / ".project").is_file():
            return directory
    return None


def project_name(project_dir: pathlib.Path) -> str:
    name = ET.parse(project_dir / ".project").getroot().findtext("name")
    if not name:
        raise ValueError(f"{project_dir / '.project'} has no <name>")
    return name


def run_papyrus(
    exe: str,
    project_dir: pathlib.Path,
    name: str,
    di_rel: str,
    output: pathlib.Path,
    timeout: int,
) -> bool:
    with tempfile.TemporaryDirectory(prefix="papyrus-ws-") as workspace:
        shutil.copytree(project_dir, pathlib.Path(workspace, name))
        cmd = [
            exe,
            "-nosplash",
            "-consoleLog",
            "-application",
            EXPORT_APPLICATION,
            "-data",
            workspace,
            "-project",
            name,
            "-di",
            di_rel,
            "-out",
            str(output),
        ]
        try:
            subprocess.run(cmd, check=False, timeout=timeout)
        except subprocess.TimeoutExpired:
            print(
                f"error: Papyrus export timed out after {timeout}s",
                file=sys.stderr,
            )
            return False
    return True


def gmf_owners(notation: ET.Element) -> dict[str, str | None]:
    owners: dict[str, str | None] = {}
    for diagram in notation:
        if diagram.tag != "{http://www.eclipse.org/gmf/runtime/1.0.3/notation}Diagram":
            continue
        xmi_id = diagram.get(f"{{{NOTATION_XMI}}}id")
        if xmi_id is None:
            continue
        owner = None
        for style in diagram.findall("styles"):
            if _xmi_type(style, NOTATION_XMI) == "style:PapyrusDiagramStyle":
                owner_ref = style.find("owner")
                if owner_ref is not None:
                    owner = _fragment(owner_ref.get("href"))
        if owner is None:
            element = diagram.find("element")
            if element is not None:
                owner = _fragment(element.get("href"))
        owners[xmi_id] = owner
    return owners


def _axis_provider(table: ET.Element, attr: str, history: str) -> ET.Element | None:
    current = table.get(attr)
    for provider in table.findall(history):
        if provider.get(f"{{{NOTATION_XMI}}}id") == current:
            return provider
    return None


def table_columns(table: ET.Element) -> list[Column]:
    provider = _axis_provider(
        table, "currentColumnAxisProvider", "columnAxisProvidersHistory"
    )
    if provider is None:
        return []
    columns: list[Column] = []
    for axis in provider.findall("axis"):
        width = COLUMN_WIDTH
        for style in axis.findall("styles"):
            if style.get("name") == "axisWidth" and style.get("intValue"):
                width = int(style.get("intValue", COLUMN_WIDTH))
        alias = axis.get("alias")
        axis_type = _xmi_type(axis, NOTATION_XMI)
        if axis_type == "nattableaxis:EStructuralFeatureAxis":
            element = axis.find("element")
            href = element.get("href", "") if element is not None else ""
            feature = href.rsplit("/", 1)[-1]
            columns.append(Column(alias or feature, width, feature))
        elif axis_type == "nattableaxis:FeatureIdAxis":
            ref = axis.get("element", "")
            if not ref.startswith(STEREOTYPE_FEATURE_PREFIX):
                continue
            segments = ref[len(STEREOTYPE_FEATURE_PREFIX) :].split("::")
            if len(segments) < 2:
                continue
            feature = segments[-1]
            columns.append(Column(alias or feature, width, feature, segments[-2]))
    return columns


def table_rows(table: ET.Element) -> list[str]:
    provider = _axis_provider(
        table, "currentRowAxisProvider", "rowAxisProvidersHistory"
    )
    if provider is None:
        return []
    rows: list[str] = []
    for axis in provider.findall("axis"):
        element = axis.find("element")
        ref = _fragment(element.get("href")) if element is not None else None
        if ref is not None:
            rows.append(ref)
    return rows


def cell_value(model: Model, row_id: str, column: Column) -> str:
    element = model.elements.get(row_id)
    if element is None:
        return ""
    if column.stereotype is not None:
        application = model.stereotypes.get((row_id, column.stereotype))
        if application is None:
            return ""
        value = application.get(column.feature.replace(" ", ""))
        if value is None:
            child = application.find(column.feature.replace(" ", ""))
            value = _fragment(child.get("href")) if child is not None else None
        return model.label(value) if value is not None else ""
    if column.feature == "default":
        default = element.find("defaultValue")
        return default.get("value", "") if default is not None else ""
    value = element.get(column.feature)
    if value is not None:
        return model.label(value)
    child = element.find(column.feature)
    if child is not None:
        return child.get("value", "")
    return ""


def render_table(model: Model, columns: list[Column], rows: list[str]) -> str:
    width = sum(c.width for c in columns) + 1
    height = ROW_HEIGHT * (len(rows) + 1) + 1
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}"'
        f' height="{height}" viewBox="0 0 {width} {height}">',
    ]
    clip = 0

    def cell(x: int, y: int, w: int, text: str, header: bool) -> None:
        nonlocal clip
        clip += 1
        fill = HEADER_FILL if header else BODY_FILL
        weight = ' font-weight="bold"' if header else ""
        text = " ".join(text.split())
        lines.extend(
            [
                f'<clipPath id="c{clip}"><rect x="{x}" y="{y}" width="{w}"'
                f' height="{ROW_HEIGHT}"/></clipPath>',
                f'<rect x="{x}" y="{y}" width="{w}" height="{ROW_HEIGHT}"'
                f' fill="{fill}" stroke="{STROKE}"/>',
                f'<g clip-path="url(#c{clip})" {FONT}{weight}>',
                f'<text x="{x + 4}" y="{y + 15}">{html.escape(text)}</text>',
                "</g>",
            ]
        )

    x = 0
    for column in columns:
        cell(x, 0, column.width, column.label, True)
        x += column.width
    for index, row in enumerate(rows, start=1):
        x = 0
        for column in columns:
            cell(
                x,
                index * ROW_HEIGHT,
                column.width,
                cell_value(model, row, column),
                False,
            )
            x += column.width
    lines.append("</svg>")
    return "\n".join(lines) + "\n"


def export_tables(
    model: Model, notation: ET.Element, output: pathlib.Path
) -> list[Entry]:
    entries: list[Entry] = []
    for table in notation:
        if table.tag != "{http://www.eclipse.org/papyrus/nattable/model}Table":
            continue
        uuid = table.get(f"{{{NOTATION_XMI}}}id")
        owner_ref = table.find("owner")
        owner = _fragment(owner_ref.get("href")) if owner_ref is not None else None
        if uuid is None or owner not in model.elements:
            continue
        name = table.get("name", "")
        columns = table_columns(table)
        rows = table_rows(table)
        success = bool(columns and rows)
        if success:
            (output / f"{uuid}.svg").write_text(
                render_table(model, columns, rows), encoding="utf-8"
            )
            print(f"  ok   {uuid} {name}")
        else:
            print(
                f"  FAIL {uuid} {name}: table has no columns or rows", file=sys.stderr
            )
        entries.append(
            Entry(uuid, name, table.get("tableKindId", ""), "table", owner, success)
        )
    return entries


def write_index(output: pathlib.Path, model: Model, entries: list[Entry]) -> None:
    index: list[dict[str, t.Any]] = [
        {
            "uuid": e.uuid,
            "format": "svg",
            "name": e.name,
            "type": e.type,
            "viewpoint": model.viewpoint(e.owner),
            "success": e.success,
            "kind": e.kind,
            "owner": e.owner,
        }
        for e in entries
    ]
    (output / "index.json").write_text(
        json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    viewpoints: dict[str, list[dict[str, t.Any]]] = {}
    for item in index:
        viewpoints.setdefault(item["viewpoint"], []).append(item)
    title = html.escape(f"Papyrus diagram cache for {model.name!r}")
    now = datetime.datetime.now().strftime("%A, %Y-%m-%d %H:%M:%S")
    parts = [
        "<!DOCTYPE html>",
        "<html><head>",
        '<meta charset="utf-8">',
        f"<title>{title}</title>",
        "<style>a:active, a:hover, a:link, a:visited {text-decoration: none}"
        " .missing {color: red} .small {font-size: 60%}"
        " .uuid {color: #AAA; font-size: 60%}</style>",
        "</head><body>",
        f"<h1>{title}</h1>",
        f'<p class="small">Created: {now}</p>',
    ]
    for viewpoint, items in sorted(viewpoints.items()):
        parts.append(f"<h2>{html.escape(viewpoint)}</h2>")
        parts.append("<ol>")
        for item in sorted(items, key=lambda i: i["name"] or ""):
            label = f"[{html.escape(item['type'] or '')}] "
            name = html.escape(item["name"] or "")
            uuid = html.escape(item["uuid"])
            if item["success"]:
                link = f'<a href="{uuid}.svg">{name}</a>'
            else:
                link = f'<span class="missing">{name}</span>'
            parts.append(f'<li>{label}{link} <span class="uuid">{uuid}</span></li>')
        parts.append("</ol>")
    parts.append("</body></html>")
    (output / "index.html").write_text("\n".join(parts) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--model", required=True, type=pathlib.Path, help="Path to the .di file"
    )
    parser.add_argument(
        "--output", required=True, type=pathlib.Path, help="Cache directory"
    )
    parser.add_argument(
        "--exe",
        default=os.environ.get("ECLIPSE_EXECUTABLE"),
        help="Papyrus executable (default: $ECLIPSE_EXECUTABLE)",
    )
    parser.add_argument(
        "--index", action="store_true", help="Write index.json and index.html"
    )
    parser.add_argument(
        "--timeout", type=int, default=1800, help="Papyrus timeout in seconds"
    )
    args = parser.parse_args(argv)

    model_path: pathlib.Path = args.model.resolve()
    if not args.exe:
        parser.error("--exe is required when ECLIPSE_EXECUTABLE is not set")
    project_dir = find_project(model_path)
    if project_dir is None:
        print(f"error: no Eclipse .project above {args.model}", file=sys.stderr)
        return 1
    name = project_name(project_dir)

    output: pathlib.Path = args.output.resolve()
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    di_rel = model_path.relative_to(project_dir).as_posix()
    if not run_papyrus(args.exe, project_dir, name, di_rel, output, args.timeout):
        return 1
    result_file = output / "export-result.json"
    if not result_file.is_file():
        print("error: Papyrus export produced no result", file=sys.stderr)
        return 1
    results = json.loads(result_file.read_text(encoding="utf-8"))
    result_file.unlink()

    model = Model(model_path.with_suffix(".uml"))
    notation = ET.parse(model_path.with_suffix(".notation")).getroot()
    owners = gmf_owners(notation)

    gmf = [r for r in results if r["kind"] == "gmf"]
    sirius = [r for r in results if r["kind"] != "gmf"]
    entries = [
        Entry(
            r["uuid"], r["name"], r["type"], "gmf", owners.get(r["uuid"]), r["success"]
        )
        for r in gmf
    ]
    entries += export_tables(model, notation, output)
    entries += [
        Entry(r["uuid"], r["name"], r["type"], r["kind"], r["owner"], r["success"])
        for r in sirius
    ]

    if args.index:
        write_index(output, model, entries)

    succeeded = sum(e.success for e in entries)
    print(f"Exported {succeeded} of {len(entries)} diagrams to {output}")
    if succeeded == len(entries):
        return 0
    if succeeded == 0:
        return 1
    return 3


if __name__ == "__main__":
    sys.exit(main())
