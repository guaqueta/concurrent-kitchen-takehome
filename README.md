# Concurrent Kitchen Simulator

## Running the simulator

```
clojure -X kitchen.core/-main
```
Push orders from `resources/orders.json` through the system. Output will
appear on the console and be logged to `log/kitchen.log`. The last line logged
will be the orders that were succesfully delivered.

```
clj
>> (require 'kitchen.core)
>> (kitchen.core/run-emulator)
```
Same as above, but via the REPL. For more fine-grained control you can call
the fns from the `kitchen.customer` namespace directly.

## Configuration

The kitchen is configured via `resources/config.edn`, which provides the
following options:
- orders-json: (string) Path to the file to read for orders to process. File must be in
  the resource-path of the project
- customer-wait-between-orders: (int) Number of milliseconds to wait between
  sending customer orders to the kitchen
- courier-minimum-wait-time: (int) Minimum number of milliseconds a courier
  will wait before picking up an order
- courier-maximum-wait-time: (int) Maximum number of milliseconds a courier
  will wait before picking up an order
- shelf-capacity: (map of ints) The number of dishes that can be stored on
  each shelf in the kitchen
  
