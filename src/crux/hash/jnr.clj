(ns crux.hash.jnr
  (:require [crux.hash :as hash]
            [crux.memory :as mem])
  (:import [org.agrona DirectBuffer ExpandableDirectByteBuffer MutableDirectBuffer]
           org.agrona.concurrent.UnsafeBuffer
           java.security.MessageDigest
           java.util.function.Supplier
           jnr.ffi.Pointer))

;; Uses libgcrypt to do the SHA1 native, can be 30% faster than
;; MessageDigest, but not necessarily with sane realistic allocation
;; patterns, might be easier to integrate once everything is in
;; buffers already. It's available by default in Ubuntu 16.04 and
;; likely many other distros.

;; See:
;; https://www.gnupg.org/documentation/manuals/gcrypt/Hashing.html#Hashing
;; https://ubuntuforums.org/archive/index.php/t-337664.html

(definterface GCrypt
  (^int gcry_md_map_name [^String name])
  (^int gcry_md_get_algo_dlen [^int algo])
  (^void gcry_md_hash_buffer [^int algo
                              ^{jnr.ffi.annotations.Out true :tag jnr.ffi.Pointer} digest
                              ^{jnr.ffi.annotations.In true :tag jnr.ffi.Pointer} buffer
                              ^{jnr.ffi.types.size_t true :tag int} length]))

(def ^:private ^GCrypt gcrypt (.load (jnr.ffi.LibraryLoader/create GCrypt) "gcrypt"))
(def ^:private ^jnr.ffi.Runtime gcrypt-rt (jnr.ffi.Runtime/getRuntime gcrypt))

(def ^:private ^:const gcrypt-hash-algo (.gcry_md_map_name gcrypt hash/id-hash-algorithm))
(assert (not (zero? gcrypt-hash-algo))
        (str "libgcrypt does not support algorithm: " hash/id-hash-algorithm))

(def ^:private ^:const gcrypt-hash-dlen (.gcry_md_get_algo_dlen gcrypt gcrypt-hash-algo))
(assert (= gcrypt-hash-dlen hash/id-hash-size)
        (format "libgcrypt and MessageDigest disagree on digest size: %d %d"
                gcrypt-hash-dlen hash/id-hash-size))

(def ^:private ^ThreadLocal buffer-tl
  (ThreadLocal/withInitial
   (reify Supplier
     (get [_]
       (ExpandableDirectByteBuffer.)))))

(defn gcrypt-id-hash-buffer ^org.agrona.DirectBuffer [^MutableDirectBuffer to ^DirectBuffer buffer]
  (let [^DirectBuffer buffer (if (mem/off-heap? buffer)
                               buffer
                               (mem/ensure-off-heap buffer (.get buffer-tl)))]
    (.gcry_md_hash_buffer gcrypt
                          gcrypt-hash-algo
                          (Pointer/wrap gcrypt-rt (.addressOffset to))
                          (Pointer/wrap gcrypt-rt (.addressOffset buffer))
                          (mem/capacity buffer))
    (mem/limit-buffer to hash/id-hash-size)))