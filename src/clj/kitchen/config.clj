(ns kitchen.config
  (:require [cprop.core]))

(def env (cprop.core/load-config))
