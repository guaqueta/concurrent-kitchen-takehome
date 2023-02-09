(ns kitchen.config
  "Manage the configuration for the system. By default `cprop` reads from a file
  called `config.edn` which is expected to be in the resource-path.

  We use `mount` to manage state lifecycles. For config `start` just means
  reading the config file, and there is nothing to `stop`. Nevertheless, it is
  convenient to reload the config whenever we restart the system."
  (:require [cprop.core]
            [mount.core :as mount]))

(defstate env (cprop.core/load-config))
