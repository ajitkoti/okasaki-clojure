# okasaki-clojure

An implementation of some data structures described in Okasaki's book "Purely 
Functional Data Structures".

I'm trying to follow _almost directly_ the ML implementations using some sugar on David Nolen's [core.match]
(https://github.com/clojure/core.match) library. 

This _sugar_ is defined in namespace datatype.core.

## Datatypes

A datatype contains an object to name it, and a group of constructors
with or without parameters.

For instance, a _datatype_ for unbalanced binary search trees can be defined as:

    (defdatatype
        ::UnbalancedBST
        Empty        
        (Node a x b)) 

and _functions_ using pattern matching over these trees as:

    (defun insert [x t]
        [x Empty] 
            (Node Empty x Empty)
        [x ([Node a y b] :as s)]
            (cond 
                (< x y) (Node (insert x a) y b)
                (< y x) (Node a y (insert x b))
                :else   s))

    (defun member [x t]
        [_ Empty]
            false
        [x [Node a y b]]
            (cond
                (< x y) (recur x a)
                (< y x) (recur x b)
                :else   true))

## Lazy constructors

Some constructors can be lazy: they are evaluated only if needed. 

To define a constructor as lazy, we associate the :lazy metadata
as true with it.

For instance, we can define the Streams type as

    (defdatatype
        ::Streams
        Nil
        (^:datatype.core/lazy Cons elem stream))

which would define the Cons constructor to be lazy.

Using it we can define a function that returns the infinite stream of naturals

    (defn nats
          ([]  (nats 0))
          ([n] (Cons n (nats (inc n)))))
          
### deflazy

When we want to define all the constructors of a datatype as lazy, we
can use deflazy as a shortcut.

    (deflazy
        ::Streams
        Nil
        (Cons elem stream))

and now both constructors would be defined as lazy.

#### (c) Juan Manuel Gimeno Illa
