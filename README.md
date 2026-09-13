# hive-olympus-emacs

Olympus in Emacs. This brick has no code: it is one manifest that mounts
`hive-olympus.harness/addon-ctor` for the host `hive.emacs`. The core
(`hive.olympus`) renders the agent grid as one `:ui/show-panel` per tab.
`hive.emacs` exposes no vessel hooks, so the manifest names the core's
built-in eval-port resolver: hive-vessel lowers each panel to one elisp
program, and `hive-emacs.client/eval-elisp!` evaluates it. Each tab is a
`*hive:olympus/tab-N*` buffer.

```
resources/META-INF/hive-addons/hive-olympus-emacs.edn
```

Requires `hive.olympus` and `hive.emacs` mounted in the same hive.
`io.github.hive-agi/hive-vessel` is a runtime dependency of this brick,
because `hive.emacs` does not bring it.

## Test

hive-olympus is not yet published, so point at a sibling checkout:

```
clojure -Sdeps "$(cat local.deps.edn)" -M:test
```

with an untracked `local.deps.edn`:

```clojure
{:deps {io.github.hive-agi/hive-olympus {:local/root "../hive-olympus"}}}
```

## Real Emacs probe

Starts a private `emacs -Q --fg-daemon=olympus-probe`, delivers a six-agent
stub grid into it through the shipped manifest, reads both tab buffers back
and kills the daemon. It never touches your own Emacs session.

```
clojure -Sdeps "$(cat local.deps.edn)" -M:probe
```

MIT licensed.
