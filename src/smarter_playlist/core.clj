(ns smarter-playlist.core
  (:require [clj-time.core :as t]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [com.github.bdesham.clj-plist :refer [parse-plist]]
            [smarter-playlist.util :as util])
  (:gen-class))

;;;; Reading the library from disk

(defn itunes-library
  "Reads the iTunes XML file and returns a Clojure data structure
  containing information about the user's iTunes library."
  []
  (->> (System/getProperty "user.home")
    (format "%s/Music/iTunes/iTunes Music Library.xml")
    (parse-plist)))

;;;; Getting attributes of songs

(defn title
  "Returns the title of a song."
  [song]
  (get song "Name"))

(defn album
  "Returns the album name of a song."
  [song]
  (get song "Album"))

(defn track-number
  "Returns the track number of a song."
  [song]
  (get song "Track Number"))

(defn comments
  "Returns the contents of a song's Comments field."
  [song]
  (get song "Comments"))

;;;; Reshaping the library

(defn library->songs
  "Converts the data structure returned by itunes-library into a
  sequence of songs, each of which can be passed to title, album,
  track-number, etc."
  [library]
  (-> library
    (get "Tracks")
    (vals)
    (->> (filter #(re-find #"\\Tag2\\"
                           (comments %))))))

(defn songs->albums
  "Converts a sequence of songs into a map from album names to
  sequences of songs. Within an album, the songs with track numbers
  come first, followed by the songs without track numbers (in
  alphabetical order). This is consistent with their display order in
  iTunes. Disks are not taken into account."
  [songs]
  (->> songs
    (group-by album)
    (reduce-kv (fn [album-map album songs]
                 (assoc album-map album
                        (sort-by (fn [song]
                                   [(nil? (track-number song))
                                    (or (track-number song)
                                        (title song))])
                                 songs)))
               {})))

;;;; Calculating things about songs

(defn age
  "Determine how long it has been since a song was last played, in
  seconds."
  [song]
  (when-let [play-date (get song "Play Date UTC")]
    (-> play-date
      (t/interval (t/now))
      (t/in-seconds))))

(defn age->weight
  "Converts the age of a song into the weight of that song when it is
  being considered for random selection."
  ([age]
   (age->weight age nil))
  ([age default]
   (if age
     (Math/pow (Math/log age) 6)
     default)))

(defn weight
  "Determines the weight of a song when it is being considered for
  random selection."
  ([song]
   (weight song nil))
  ([song default]
   (age->weight (age song) default)))

;;;; Generating playlists

(defn next-song
  "Given a song list and album map (as returned by library->songs and
  songs->albums, respectively), a current song (or nil), and a keyword
  representing the strategy to use, selects another song and returns
  it (or nil). The :next-in-album strategy means to return the song
  coming directly after the current song in the same album, or nil if
  the current song was nil or the last song in the album.
  The :random-in-album strategy means to return a random song in the
  album of the current song, or nil if the current song was nil.
  The :random strategy means to return a random song across the entire
  library, weighted according to age (via age->weight), regardless of
  the current song."
  [songs album-map song strategy]
  (case strategy
    :next-in-album
    (->> (get album-map (album song))
      (drop-while #(not= (title %)
                         (title song)))
      (second))

    :random-in-album
    (rand-nth (get album-map (album song)))

    :random
    (let [oldest (->> songs
                   (map age)
                   (remove nil?)
                   (apply max))]
      (util/weighted-rand-nth
        songs
        (map (fn [song]
               (age->weight
                 (or (age song)
                     oldest)))
             songs)))))

(def default-strategy-weights
  "Default value of strategy-weights used by playlist."
  {:next-in-album 100
   :random-in-album 2
   :random 20})

(defn playlist
  "Returns a playlist of the given length drawn from the given song
  list (as returned by library->songs). The playlist is generated by
  picking a (weighted) random song and calling next-song repeatedly
  with strategies selected from the strategy-weights map, whose keys
  are the strategy keywords (see next-song) and whose values are their
  weights for random selection."
  [songs length & [strategy-weights]]
  (let [strategy-weights (or strategy-weights
                             default-strategy-weights)
        albums (songs->albums songs)]
    (->> strategy-weights
      ((juxt keys vals))
      (apply util/weighted-rand-nth)
      (fn [])
      (repeatedly)
      (cons :random)
      (reductions (partial next-song songs albums) nil)
      (remove nil?)
      (take (dec length)))))

;;;; Interacting with AppleScript

(defn escape
  "Escapes a string for embedding within an AppleScript string
  literal."
  [s]
  (str/replace s "\"" "\\\""))

(defn run-applescript
  "Given a sequence of lines of AppleScript code, shells out to run
  them."
  [lines]
  (->> lines
    (interleave (repeat "-e"))
    (apply sh "osascript")))

(defn format-applescript
  "Convenience function for generating AppleScript code to pass to
  run-applescript."
  [lines+args]
  (map #(apply format %) lines+args))

;;;; Controlling iTunes via AppleScript
;;; Based on http://apple.stackexchange.com/a/77626/184150
;;; and lots of other random websites

(defn make-empty-playlist
  "Creates an empty iTunes playlist with the given name, or clears
  the playlist by that name if it already exists."
  [playlist-name]
  (run-applescript
    (format-applescript
      [["tell application \"iTunes\""]
       ["    if user playlist \"%s\" exists then" playlist-name]
       ["        try"]
       ["            delete tracks of user playlist \"%s\"" playlist-name]
       ["        end try"]
       ["    else"]
       ["        make new user playlist with properties {name:\"%s\"}" playlist-name]
       ["    end if"]
       ["end tell"]])))

;;;; iTunes AppleScript

(defn add-songs-to-playlist
  "Adds the given songs to the given playlist. WARNING: If there are
  two or more songs in your iTunes library with the same title and
  album name, then this function might add the wrong one to the
  playlist."
  [playlist-name songs]
  (run-applescript
    (format-applescript
      `[["set thePlaylistName to \"%s\"" ~(escape playlist-name)]
        [""]
        ["tell application \"iTunes\""]
        ~@(map (fn [song]
                 ["    my handleSong(\"%s\", \"%s\")"
                  (escape (title song))
                  (escape (album song))])
               songs)
        ["    my handleSong(\"Silence\", \"F-Zero Remixed\")"]
        ["end tell"]
        [""]
        ["on handleSong(theSongName, theAlbumName)"]
        ["    tell application \"iTunes\""]
        ["        set filteredSongs to (every track of library playlist 1 whose name is theSongName)"]
        ["        repeat with theFilteredSong in filteredSongs"]
        ["            if the album of theFilteredSong is theAlbumName then"]
        ["                duplicate theFilteredSong to playlist (my thePlaylistName)"]
        ["                return"]
        ["            end if"]
        ["        end repeat"]
        ["    end tell"]
        ["    display dialog \"Uh, oh. Couldn't find '\" & theSongName & \"' by '\" & theAlbumName & \"'!\""]
        ["end handleSong"]])))

;;;; Main entry point

(defn create-and-save-playlist
  "Create a playlist according to the given parameters (see playlist),
  make an empty iTunes playlist of the given name (see
  make-empty-playlist), and fill it with the songs in the generated
  playlist (see add-songs-to-playlist)."
  [& {:keys [length playlist-name strategy-weights]
      :or {length 100
           playlist-name "Smarter Playlist"}}]
  (printf "Creating playlist of length %d with strategy weights %s and saving it as \"%s\"...%n"
          length
          (or strategy-weights default-strategy-weights)
          playlist-name)
  (flush)
  (doto playlist-name
    (make-empty-playlist)
    (add-songs-to-playlist
      (-> (itunes-library)
        (library->songs)
        (playlist length strategy-weights)))))

(defn -main
  "Main entry point. Command line arguments are concatenated with
  spaces, the resulting string is parsed as a sequence of EDN data
  structures, and the data structures are passed as arguments to
  create-and-save-playlist."
  [& args]
  (->> args
    (str/join \space)
    (util/read-all)
    (apply create-and-save-playlist))
  ;; The following prevents a minute-long hang that keeps Clojure from
  ;; exiting after finishing:
  (shutdown-agents))
