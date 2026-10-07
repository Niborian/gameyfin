"""Generate synthetic source files and offline H2 seed SQL; never opens a database.

Run on an isolated CI/staging host. Import SQL only into a freshly migrated,
stopped-service fixture database. No production paths or credentials are inputs.
"""
import argparse
import hashlib
import json
from pathlib import Path


def generate(output: Path, container_root: str):
    if output.exists():
        raise ValueError("Output must be a new isolated directory")
    if not container_root.startswith("/") or "'" in container_root or ".." in container_root.split("/"):
        raise ValueError("Container fixture root must be an absolute safe path")
    output.mkdir(parents=False)
    sources = output / "sources"
    sources.mkdir()
    statements = []
    manifest = {}
    games = []
    for library, count in enumerate((104, 27, 1, 0), start=1):
        library_id = 27000 + library
        library_path = f"{container_root.rstrip('/')}/lib{library}"
        (sources / f"lib{library}").mkdir()
        statements.extend([
            f"INSERT INTO LIBRARY(ID,CREATED_AT,NAME,UPDATED_AT,STORAGE_MODE) VALUES({library_id},CURRENT_TIMESTAMP,'Synthetic library {library}',CURRENT_TIMESTAMP,'DIRECT');",
            f"INSERT INTO DIRECTORY_MAPPING(ID,EXTERNAL_PATH,INTERNAL_PATH) VALUES({library_id},'{library_path}','{library_path}');",
            f"INSERT INTO LIBRARY_DIRECTORIES(LIBRARY_ID,DIRECTORIES_ID) VALUES({library_id},{library_id});",
        ])
        for index in range(count):
            game_id = 30000 + library * 1000 + index
            relative = f"lib{library}/game-{index:03d}"
            directory = sources / relative
            directory.mkdir()
            path = f"{library_path}/game-{index:03d}"
            for version in ("1.0", "1.1"):
                variant = directory / f"Normal {version}"
                variant.mkdir()
                (variant / "game.bin").write_bytes((f"{game_id}-{version}\n".encode() * 4096)[:32768])
                (variant / "metadata.txt").write_text(
                    f"variant=Normal\nversion={version}\ncontent.base.type=BASE\ncontent.base.path=game.bin\ncontent.base.required=true\n", encoding="utf-8")
            for name in ("base-a.bin", "base-b.bin", "patch.bin"):
                (directory / name).write_bytes((f"{game_id}-{name}\n".encode() * 2048)[:16384])
            variant_id = game_id + 100000
            base_id = game_id * 2 + 200000
            statements.extend([
                f"INSERT INTO GAME(ID,CREATED_AT,TITLE,UPDATED_AT,PATH,FILE_SIZE,LIBRARY_ID,DOWNLOAD_COUNT,MATCH_CONFIRMED) VALUES({game_id},CURRENT_TIMESTAMP,'Synthetic game {game_id}',CURRENT_TIMESTAMP,'{path}',49152,{library_id},0,TRUE);",
                f"INSERT INTO GAME_VARIANT(ID,FILE_SIZE,IS_DEFAULT,IS_LATEST_FOR_VARIANT,NAME,PATH,VERSION,GAME_ID,SCAN_MANAGED,DEFAULT_LOCKED,LINK_STATUS) VALUES({variant_id},49152,TRUE,TRUE,'Normal','{path}/Normal 1.0','1.0',{game_id},FALSE,TRUE,'DIRECT');",
                f"INSERT INTO VARIANT_CONTENT(ID,DEFAULT_SELECTED,FILE_SIZE,NAME,PATH,REQUIRED,TYPE,VARIANT_ID) VALUES({base_id},TRUE,32768,'Grouped base','{path}/base-a.bin',TRUE,'BASE',{variant_id}),({base_id+1},FALSE,16384,'Optional patch','{path}/patch.bin',FALSE,'PATCH',{variant_id});",
                f"INSERT INTO VARIANT_CONTENT_PATHS(VARIANT_CONTENT_ID,PATH_INDEX,PATH) VALUES({base_id},0,'{path}/base-a.bin'),({base_id},1,'{path}/base-b.bin'),({base_id+1},0,'{path}/patch.bin');",
            ])
            games.append({"gameId": game_id, "variantId": variant_id, "requiredContentId": base_id,
                          "optionalContentId": base_id + 1, "path": path,
                          "requiredMembers": [f"{relative}/base-a.bin", f"{relative}/base-b.bin"],
                          "optionalMembers": [f"{relative}/patch.bin"],
                          "requiredArchiveMembers": {"Grouped base/base-a.bin": f"{relative}/base-a.bin",
                              "Grouped base/base-b.bin": f"{relative}/base-b.bin"},
                          "optionalArchiveMembers": {"Optional patch.bin": f"{relative}/patch.bin"}})
    for file in sorted(sources.rglob("*")):
        if file.is_file():
            manifest[file.relative_to(sources).as_posix()] = hashlib.sha256(file.read_bytes()).hexdigest()
    (output / "seed.sql").write_text("\n".join(statements) + "\n", encoding="utf-8")
    (output / "manifest.json").write_text(json.dumps({"scope": "synthetic offline fixture",
        "libraryCounts": [104, 27, 1, 0], "gameCount": 132, "variantCount": 132,
        "contentCount": 264, "sourceFileCount": 924, "sha256": manifest,
        "games": games}, indent=2) + "\n", encoding="utf-8")
    assert len(games) == 132 and len(manifest) == 924


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--container-fixture-root", default="/fixture")
    arguments = parser.parse_args()
    generate(arguments.output, arguments.container_fixture_root)
