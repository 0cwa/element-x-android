#!/usr/bin/env python3
"""Export the audited widget DAG onto a pinned upstream commit, without rewriting refs."""

import base64
import json
import os
from pathlib import Path
import re
import subprocess

UPSTREAM = "078b6176efcb55c44a60919543104a75a145db93"
REPOSITORY = "0cwa/element-x-android"
UNITS = [
    ("A", "capability-policy", "262914e6b782ee1fa00a8b79c0cfae9d83452f7e", []),
    ("B", "transport-extraction", "4915bbd082c14129df7d7d009923e1a8b04cc3b5", []),
    ("C", "room-state", "6623105ba131dca120b7309d44462b439880d0de", []),
    ("D", "secure-transport", "3c3a4e102c98ac9187f7f8a07851c5f1f6c7a5c6", ["B"]),
    ("E", "host-foundations", "1099362bb75417d5cf813b1f0b8f9b7fe48fd9ff", ["A"]),
    ("F", "host-permissions", "a83acc79f5caaffb744a8011209e7a3d713b44bb", ["E", "D"]),
    ("G", "host-runtime", "b96fe405b7b6563192349615cc44ec50e90b1dab", ["F"]),
    ("H", "host-screen", "2cddd02cca6f2c7a09d3c9c035ebe83200ff8cd1", ["F"]),
    ("I", "host-activity", "f421497be1436f7f38d0e8c7d394f45b5ca51237", ["G", "H"]),
    ("J", "discovery", "084893e76d9e4612708ab1353df691922d53a34f", ["E", "D", "C"]),
    ("K", "extensions-navigation", "972cb422a35c9142f17778d54d83f1a296ecf575", ["I", "J"]),
]


def git(*args, env=None):
    result = subprocess.run(["git", *args], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, env=env, check=False)
    if result.returncode:
        raise RuntimeError(f"git {args[0]} failed:\n{result.stdout}")
    return result.stdout.strip()


def resolve_room_state_dependencies():
    """Resolve only the inspected duplicate-serialization conflict, never arbitrary conflicts."""
    path = "libraries/matrix/impl/build.gradle.kts"
    unmerged = git("diff", "--name-only", "--diff-filter=U").splitlines()
    if unmerged != [path]:
        raise RuntimeError(f"Unreviewed merge conflict: {unmerged}")
    content = git("show", f"{UPSTREAM}:{path}") + "\n"
    for anchor, addition in [
        ("    implementation(libs.serialization.json)\n",
         "    implementation(platform(libs.network.okhttp.bom))\n    implementation(libs.network.okhttp)\n"),
        ("    testImplementation(projects.libraries.previewutils)\n",
         "    testImplementation(libs.network.mockwebserver)\n"),
    ]:
        if content.count(anchor) != 1:
            raise RuntimeError(f"The reviewed Gradle anchor changed: {anchor}")
        content = content.replace(anchor, anchor + addition)
    Path(path).write_text(content)
    git("add", path)
    print("Resolved the reviewed Matrix Gradle overlap; retained upstream plus room-state dependencies.", flush=True)


def is_test_or_generated(path):
    return (path.startswith(("tests/", "screenshots/"))
            or "/src/test/" in path or "/src/androidTest/" in path
            or "/test/" in path)


def module_tasks(paths):
    tasks = {":app:compileGplayDebugKotlin", ":app:compileFdroidDebugKotlin",
             ":tests:konsist:testDebugUnitTest", "ktlintCheck"}
    for filename in paths:
        for parent in Path(filename).parents:
            if parent == Path("."):
                break
            if (parent / "build.gradle.kts").is_file():
                if str(parent) not in {"app", "tests/uitests"}:
                    module = ":" + str(parent).replace("/", ":")
                    tasks.update({module + ":compileDebugKotlin", module + ":testDebugUnitTest"})
                break
    return sorted(tasks)


def main():
    if os.environ.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise RuntimeError("This preparation job is restricted to the development fork")
    run_id = os.environ["GITHUB_RUN_ID"]
    if not re.fullmatch(r"[0-9]+", run_id):
        raise RuntimeError("Invalid workflow run ID")
    report_dir = Path(os.environ["RUNNER_TEMP"]) / "widget-export"
    report_dir.mkdir(parents=True, exist_ok=True)
    prefix = f"submit/widget-{run_id}"
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("config", "commit.gpgsign", "false")
    git("remote", "set-url", "origin", f"https://github.com/{REPOSITORY}.git")
    git("fetch", "--no-tags", "origin", *[unit[2] for unit in UNITS])
    git("fetch", "--no-tags", "https://github.com/element-hq/element-x-android.git", UPSTREAM)
    # Stable committer dates make re-running an export idempotent; original authors are retained.
    date = git("show", "-s", "--format=%cI", UPSTREAM)
    os.environ["GIT_COMMITTER_DATE"] = date
    heads, refs, manifest = {}, [], []
    for unit, slug, source, dependencies in UNITS:
        git("checkout", "--detach", heads[dependencies[0]] if dependencies else UPSTREAM)
        for dependency in dependencies[1:]:
            git("merge", "--no-ff", "--no-edit", "-m",
                f"Join prerequisites for widget unit {unit}\n\nCombine only the audited dependency branches; no implementation changes.",
                heads[dependency])
        base = git("rev-parse", "HEAD")
        try:
            git("cherry-pick", "-x", source)
        except RuntimeError:
            if unit != "C":
                raise
            resolve_room_state_dependencies()
            git("cherry-pick", "--continue")
        head = git("rev-parse", "HEAD")
        git("merge-base", "--is-ancestor", UPSTREAM, head)
        git("diff", "--check", base, head)
        paths = git("diff", "--name-only", base, head).splitlines()
        if any(path.startswith((".github/", ".maestro/", "tools/widget/")) for path in paths):
            raise RuntimeError(f"Fork automation leaked into unit {unit}")
        production = sum(int(line.split("\t", 2)[0])
                         for line in git("diff", "--numstat", "-M", base, head).splitlines()
                         if line.split("\t", 2)[0].isdigit()
                         and not is_test_or_generated(line.split("\t", 2)[2]))
        if production > 500:
            raise RuntimeError(f"Unit {unit} exceeds the production-addition limit: {production}")
        branch = f"{prefix}/{unit.lower()}-{slug}"
        base_branch = f"{prefix}/base-{unit.lower()}"
        refs.extend([f"{head}:refs/heads/{branch}", f"{base}:refs/heads/{base_branch}"])
        heads[unit] = head
        entry = dict(unit=unit, branch=branch, base_branch=base_branch, sha=head,
                     base_sha=base, source=source, dependencies=dependencies,
                     production_additions=production, files=paths, tasks=module_tasks(paths))
        manifest.append(entry)
        print(json.dumps({key: value for key, value in entry.items() if key not in {"files", "tasks"}}), flush=True)
    # Independently merge upstream into the original final candidate, then compare full trees.
    git("checkout", "--detach", UNITS[-1][2])
    try:
        git("merge", "--no-ff", "--no-edit", "-m",
            "Validate widget export source parity\n\nCompare the exported DAG with an independent upstream merge.", UPSTREAM)
    except RuntimeError:
        resolve_room_state_dependencies()
        git("commit", "--no-edit")
    git("diff", "--exit-code", "HEAD^{tree}", heads["K"] + "^{tree}")
    report = {"upstream": UPSTREAM, "source_parity": True, "candidates": manifest}
    (report_dir / "manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    # The token is supplied only to this single push; it is never persisted in the repository.
    push_env = dict(os.environ)
    push_env["GIT_CONFIG_COUNT"] = "1"
    push_env["GIT_CONFIG_KEY_0"] = "http.https://github.com/.extraheader"
    credential = base64.b64encode(("x-access-token:" + os.environ["GH_TOKEN"]).encode()).decode()
    push_env["GIT_CONFIG_VALUE_0"] = "AUTHORIZATION: basic " + credential
    git("push", "--atomic", "origin", *refs, env=push_env)
    remote = dict(line.split("\t", 1)[::-1] for line in git("ls-remote", "--heads", "origin", prefix + "/*").splitlines())
    for entry in manifest:
        if remote.get("refs/heads/" + entry["branch"]) != entry["sha"]:
            raise RuntimeError(f"Published ref verification failed for {entry['unit']}")
    matrix = {"include": [{key: entry[key] for key in ("unit", "sha", "tasks")} for entry in manifest]}
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write("matrix=" + json.dumps(matrix, separators=(",", ":")) + "\n")
        output.write("integration_sha=" + heads["K"] + "\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
        summary.write(f"## Widget submission exports\n\nPinned upstream: `{UPSTREAM}`. Full-tree parity verified. No historical refs rewritten.\n\n")
        summary.write("| Unit | Head | Production additions | Exact incremental diff |\n|---|---|---:|---|\n")
        for entry in manifest:
            compare = f"https://github.com/{REPOSITORY}/compare/{entry['base_sha']}...{entry['sha']}"
            summary.write(f"| {entry['unit']} | `{entry['sha']}` | {entry['production_additions']} | [Diff]({compare}) |\n")


if __name__ == "__main__":
    main()
