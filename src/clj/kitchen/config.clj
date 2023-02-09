(ns kitchen.config
  "Manage the configuration for the system. By default `cprop` reads from a file
  called `config.edn` which is expected to be in the resource-path. "
  (:require [cprop.core]))

(def env (cprop.core/load-config))
