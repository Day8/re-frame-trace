(ns day8.re-frame.trace
  (:require [day8.re-frame.trace.subvis :as subvis]
            [day8.re-frame.trace.styles :as styles]
            [day8.re-frame.trace.components :as components]
            [day8.re-frame.trace.localstorage :as localstorage]
            [re-frame.trace :as trace :include-macros true]
            [cljs.pprint :as pprint]
            [clojure.string :as str]
            [clojure.set :as set]
            [reagent.core :as r]
            [reagent.interop :refer-macros [$ $!]]
            [reagent.impl.util :as util]
            [reagent.impl.component :as component]
            [reagent.impl.batching :as batch]
            [reagent.ratom :as ratom]
            [goog.object :as gob]
            [re-frame.interop :as interop]

            [devtools.formatters.core :as devtools]))


;; from https://github.com/reagent-project/reagent/blob/3fd0f1b1d8f43dbf169d136f0f905030d7e093bd/src/reagent/impl/component.cljs#L274
(defn fiber-component-path [fiber]
  (let [name   (some-> fiber
                       ($ :type)
                       ($ :displayName))
        parent (some-> fiber
                       ($ :return))
        path   (some-> parent
                       fiber-component-path
                       (str " > "))
        res    (str path name)]
    (when-not (empty? res) res)))

(defn component-path [c]
  ;; Alternative branch for React 16
  (if-let [fiber (some-> c ($ :_reactInternalFiber))]
    (fiber-component-path fiber)
    (component/component-path c)))

(defn comp-name [c]
  (let [n (or (component-path c)
              (some-> c .-constructor util/fun-name))]
    (if-not (empty? n)
      n
      "")))


(def static-fns
  {:render
   (fn render []
     (this-as c
       (let [path (component-path c)]
         (trace/with-trace {:op-type   :render
                            :tags      {:component-path path}
                            :operation (last (str/split path #" > "))}
                           (if util/*non-reactive*
                             (reagent.impl.component/do-render c)
                             (let [rat        ($ c :cljsRatom)
                                   _          (batch/mark-rendered c)
                                   res        (if (nil? rat)
                                                (ratom/run-in-reaction #(reagent.impl.component/do-render c) c "cljsRatom"
                                                                       batch/queue-render reagent.impl.component/rat-opts)
                                                (._run rat false))
                                   cljs-ratom ($ c :cljsRatom)] ;; actually a reaction
                               (trace/merge-trace!
                                 {:tags {:reaction      (interop/reagent-id cljs-ratom)
                                         :input-signals (when cljs-ratom
                                                          (map interop/reagent-id (gob/get cljs-ratom "watching" :none)))}})
                               res))))))})


(defn monkey-patch-reagent []
  (let [#_#_real-renderer reagent.impl.component/do-render
        real-custom-wrapper reagent.impl.component/custom-wrapper
        real-next-tick      reagent.impl.batching/next-tick
        real-schedule       reagent.impl.batching/schedule]


    #_(set! reagent.impl.component/do-render
            (fn [c]
              (let [name (comp-name c)]
                (js/console.log c)
                (trace/with-trace {:op-type   :render
                                   :tags      {:component-path (component-path c)}
                                   :operation (last (str/split name #" > "))}
                                  (real-renderer c)))))



    (set! reagent.impl.component/static-fns static-fns)

    (set! reagent.impl.component/custom-wrapper
          (fn [key f]
            (case key
              :componentWillUnmount
              (fn [] (this-as c
                       (trace/with-trace {:op-type   key
                                          :operation (last (str/split (comp-name c) #" > "))
                                          :tags      {:component-path (component-path c)
                                                      :reaction       (interop/reagent-id ($ c :cljsRatom))}})
                       (.call (real-custom-wrapper key f) c c)))

              (real-custom-wrapper key f))))

    #_(set! reagent.impl.batching/next-tick (fn [f]
                                              (real-next-tick (fn []
                                                                (trace/with-trace {:op-type :raf}
                                                                                  (f))))))

    #_(set! reagent.impl.batching/schedule schedule
            #_(fn []
                (reagent.impl.batching/do-after-render (fn [] (trace/with-trace {:op-type :raf-end})))
                (real-schedule)))))


(def traces (interop/ratom []))

(defn log-trace? [trace]
  (let [rendering? (= (:op-type trace) :render)]
    (if-not rendering?
      true
      (not (str/includes? (or (get-in trace [:tags :component-path]) "") "devtools outer")))


    #_(if-let [comp-p (get-in trace [:tags :component-path])]
        (println comp-p))))

(defn disable-tracing! []
  (re-frame.trace/remove-trace-cb ::cb))

(defn enable-tracing! []
  (re-frame.trace/register-trace-cb ::cb (fn [new-traces]
                                           (let [new-traces (filter log-trace? new-traces)]
                                             (swap! traces #(reduce conj % new-traces))))))

(defn init-tracing!
  "Sets up any initial state that needs to be there for tracing. Does not enable tracing."
  []
  (monkey-patch-reagent))


(defn search-input [{:keys [title on-save on-change on-stop]}]
  (let [val  (r/atom title)
        save #(let [v (-> @val str str/trim)]
                (when (pos? (count v))
                  (on-save v)))]
    (fn []
      [:input {:type        "text"
               :value       @val
               :auto-focus  true
               :on-change   #(do (reset! val (-> % .-target .-value))
                                 (on-change %))
               :on-key-down #(case (.-which %)
                               13 (do
                                    (save)
                                    (reset! val ""))
                               nil)}])))

(defn query->fn [query]
  (if (= :contains (:filter-type query))
    (fn [trace]
      (str/includes? (str/lower-case (str (:operation trace) " " (:op-type trace)))
                     (:query query)))
    (fn [trace]
      (< (:query query) (:duration trace)))))

(defn add-filter [filter-items filter-input filter-type]
  ;; prevent duplicate filter strings
  (when-not (some #(= filter-input (:query %)) @filter-items)
    ;; if existing, remove prior filter for :slower-than
    (when (and (= :slower-than filter-type)
               (some #(= filter-type (:filter-type %)) @filter-items))
      (swap! filter-items (fn [item]
                            (remove #(= :slower-than (:filter-type %)) item))))
    ;; add new filter
    (swap! filter-items conj {:id          (random-uuid)
                              :query       (if (= filter-type :contains)
                                             (str/lower-case filter-input)
                                             (js/parseFloat filter-input))
                              :filter-type filter-type})))

(defn ^string truncate-string
  "Truncate a string to length `n`.

  Removal occurs at `cut-from`, which may be :start, :end, or :middle.

  Truncation is indicated by `…` at start/end, or `...` at middle, for readability. "
  ([n string]
   (n :end string))
  ([n cut-from string]
   (let [c (count string)]
     (if (> c n)
       (case cut-from
         :start (str "…" (subs string (- c (dec n)) c))
         :end (str (subs string 0 (dec n)) "…")
         :middle (case n
                   1 "…"
                   2 (truncate-string n :start string)
                   3 (str (subs string 0 1) "…" (subs string (dec c) c))
                   (let [content-budget (- n 2)
                         per-side-budget (-> content-budget
                                             (/ 2)
                                             (js/Math.floor))]
                     ;; 100 - 9 = 91 / 2 = 45
                     ;; subs string 0
                     (str (subs string 0 (cond-> per-side-budget
                                                 (even? content-budget)
                                                 (dec)))
                          "..."
                          (subs string (- c per-side-budget) c)))))
       string))))

(comment
  (assert (= (truncate-string 5 :start "123456789") "…6789"))
  (assert (= (truncate-string 5 :end "123456789") "1234…"))

  ;; special case use of … for short :middle-truncated strings
  (assert (= (truncate-string 1 :middle "123456789") "…"))
  (assert (= (truncate-string 2 :middle "123456789") "…9"))
  (assert (= (truncate-string 3 :middle "123456789") "1…9"))

  (assert (= (truncate-string 4 :middle "123456789") "...9"))
  (assert (= (truncate-string 5 :middle "123456789") "1...9"))
  (assert (= (truncate-string 6 :middle "123456789") "1...89"))
  (assert (= (truncate-string 7 :middle "123456789") "12...89"))
  (assert (= (truncate-string 8 :middle "123456789") "12...789")))


(defn ^string truncate-named
  [n named]
  (let [the-ns (namespace named)
        the-name (name named)
        kw? (keyword? named)
        ns-prefix-size (if kw? 3 2)]
    (if (or (> (count the-name) (if the-ns (- n ns-prefix-size) n))
            (nil? the-ns))
      (let [prefix (cond-> (if kw? ":" "")
                           the-ns (str "…/"))]
        (str prefix
             (truncate-string (- n (count prefix)) :start the-name)))
      (let [end (str "/" the-name)
            prefix (if kw? ":" "")
            ns-budget (- n (count end) (count prefix))]
        (str prefix
             (truncate-string ns-budget :start the-ns)
             end)))))
(defn ^string truncate [n location param]
  (if (satisfies? INamed param)
    (truncate-named n param)
    (truncate-string n location (str param))))

(comment

  (assert (= (truncate-named 1 :saskatoon) ":…"))
  (assert (= (truncate-named 2 :saskatoon) ":…"))
  (assert (= (truncate-named 3 :saskatoon) ":…n"))
  (assert (= (truncate-named 9 :saskatoon) ":…skatoon"))
  (assert (= (truncate-named 10 :saskatoon) ":saskatoon"))


  (assert (= (truncate-named 1 :city/saskatoon) ":…/…"))
  (assert (= (truncate-named 2 :city/saskatoon) ":…/…"))
  (assert (= (truncate-named 3 :city/saskatoon) ":…/…"))
  (assert (= (truncate-named 4 :city/saskatoon) ":…/…"))
  (assert (= (truncate-named 5 :city/saskatoon) ":…/…n"))
  (assert (= (truncate-named 11 :city/saskatoon) ":…/…skatoon"))
  (assert (= (truncate-named 12 :city/saskatoon) ":…/saskatoon"))
  (assert (= (truncate-named 13 :city/saskatoon) ":…y/saskatoon"))
  (assert (= (truncate-named 14 :city/saskatoon) ":…ty/saskatoon"))
  (assert (= (truncate-named 15 :city/saskatoon) ":city/saskatoon"))
  (assert (= (truncate-named 16 :city/saskatoon) ":city/saskatoon"))

  (assert (= (truncate-named 8 'saskatoon) "…skatoon"))
  (assert (= (truncate-named 9 'saskatoon) "saskatoon"))
  (assert (= (truncate-named 10 'saskatoon) "saskatoon"))

  (assert (= (truncate-named 1  'city/saskatoon) "…/…"))
  (assert (= (truncate-named 2  'city/saskatoon) "…/…"))
  (assert (= (truncate-named 3  'city/saskatoon) "…/…"))
  (assert (= (truncate-named 4  'city/saskatoon) "…/…n"))
  (assert (= (truncate-named 10 'city/saskatoon) "…/…skatoon"))
  (assert (= (truncate-named 11 'city/saskatoon) "…/saskatoon"))
  (assert (= (truncate-named 12 'city/saskatoon) "…y/saskatoon"))
  (assert (= (truncate-named 13 'city/saskatoon) "…ty/saskatoon"))
  (assert (= (truncate-named 14 'city/saskatoon) "city/saskatoon"))
  (assert (= (truncate-named 15 'city/saskatoon) "city/saskatoon")))

(defn edges
  "Return left and right edges of a collection (eg. brackets plus prefixes), defaults to [< >]."
  [coll]
  (cond (map? coll) [\{ \}]
        (vector? coll) [\[ \]]
        (set? coll) ["#{" \}]
        (list? coll) ["(" ")"]
        :else ["<" ">"]))

(defn with-edges
  "Wrap `value` with edges of `coll`"
  [coll value]
  (let [[left right] (edges coll)]
    (str left value right)))

(defn preview-param
  "Render parameters in abbreviated form, showing content only for keywords/strings/symbols and entering vectors to a depth of 1."
  ([param] (preview-param 0 vector? 1 param))
  ([depth enter-pred max-depth param]
   (cond
     (satisfies? INamed param) (truncate-named 16 param)
     (string? param) (truncate-string 16 :middle param)
     (fn? param) (or (some-> (.-name param)
                             (str/replace #"(^.*\$)(.*)" "$2"))
                     "ƒ")
     (and (enter-pred param)
          (< depth max-depth)) (with-edges param
                                           (str/join ", " (mapv (partial preview-param (inc depth) enter-pred max-depth) param)))
     :else (with-edges param "…"))))

(defn render-traces [visible-traces filter-items filter-input trace-detail-expansions]
  (doall
    (->>
      visible-traces
      (map-indexed (fn [index {:keys [op-type id operation tags duration] :as trace}]
                     (let [show-row? (get-in @trace-detail-expansions [:overrides id]
                                             (:show-all? @trace-detail-expansions))
                           op-name   (if (vector? operation)
                                       (second operation)
                                       operation)
                           #_#__ (js/console.log (devtools/header-api-call tags))]
                       (list [:tr {:key      id
                                   :on-click (fn [ev]
                                               (swap! trace-detail-expansions update-in [:overrides id]
                                                      #(if show-row? false (not %))))
                                   :class    (str/join " " ["trace--trace"
                                                            (case op-type
                                                              :sub/create "trace--sub-create"
                                                              :sub/run "trace--sub-run"
                                                              :event "trace--event"
                                                              :render "trace--render"
                                                              :re-frame.router/fsm-trigger "trace--fsm-trigger"
                                                              nil)])}

                              [:td.trace--toggle
                               [:button (if show-row? "▼" "▶")]]
                              [:td.trace--op
                               [:span.op-string {:on-click (fn [ev]
                                                             (add-filter filter-items (name op-type) :contains)
                                                             (.stopPropagation ev))}
                                (str op-type)]]
                              [:td.trace--op-string
                               [:span.op-string {:on-click (fn [ev]
                                                             (add-filter filter-items (name op-name) :contains)
                                                             (.stopPropagation ev))}
                                (str (truncate 16 :middle op-name) " ")
                                [:span
                                 {:style {:opacity 0.5
                                          :display "inline-block"}}
                                 (when-let [[_ & params] (or (get tags :query-v)
                                                             (get tags :event))]
                                   (->> (map preview-param params)
                                        (str/join ", ")
                                        (truncate-string :middle 40)))]]]
                              [:td.trace--meta
                               (.toFixed duration 1) " ms"]]
                             (when show-row?
                               [:tr.trace--details {:key       (str id "-details")
                                                    :tab-index 0}
                                [:td]
                                [:td.trace--details-tags {:col-span 2
                                                          :on-click #(.log js/console tags)}
                                 [:div.trace--details-tags-text
                                  (let [tag-str           (with-out-str (pprint/pprint tags))
                                        string-size-limit 400]
                                    (if (< string-size-limit (count tag-str))
                                      (str (subs tag-str 0 string-size-limit) " ...")
                                      tag-str))]]
                                [:td.trace--meta.trace--details-icon
                                 {:on-click #(.log js/console tags)}]]))))))))

(defn render-trace-panel []
  (let [filter-input            (r/atom "")
        filter-items            (r/atom (localstorage/get "filter-items" []))
        filter-type             (r/atom :contains)
        input-error             (r/atom false)
        categories              (r/atom #{:event :sub/run :sub/create})
        trace-detail-expansions (r/atom {:show-all? false :overrides {}})]
    (add-watch filter-items
               :update-localstorage
               (fn [_ _ _ new-state]
                 (localstorage/save! "filter-items" new-state)))
    (fn []
      (let [toggle-category-fn (fn [category-keys]
                                 (swap! categories #(if (set/superset? % category-keys)
                                                      (set/difference % category-keys)
                                                      (set/union % category-keys))))

            visible-traces     (cond->> @traces
                                        (seq @categories) (filter (fn [trace] (when (contains? @categories (:op-type trace)) trace)))
                                        (seq @filter-items) (filter (apply every-pred (map query->fn @filter-items))))
            save-query         (fn [_]
                                 (if (and (= @filter-type :slower-than)
                                          (js/isNaN (js/parseFloat @filter-input)))
                                   (reset! input-error true)
                                   (do
                                     (reset! input-error false)
                                     (add-filter filter-items @filter-input @filter-type))))]
        [:div.tab-contents
         [:div.filter
          [:div.filter-control
           [:ul.filter-categories "show: "
            [:li.filter-category {:class    (when (contains? @categories :event) "active")
                                  :on-click #(toggle-category-fn #{:event})}
             "events"]
            [:li.filter-category {:class    (when (contains? @categories :sub/run) "active")
                                  :on-click #(toggle-category-fn #{:sub/run :sub/create})}
             "subscriptions"]
            [:li.filter-category {:class    (when (contains? @categories :render) "active")
                                  :on-click #(toggle-category-fn #{:render})}
             "reagent"]
            [:li.filter-category {:class    (when (contains? @categories :re-frame.router/fsm-trigger) "active")
                                  :on-click #(toggle-category-fn #{:re-frame.router/fsm-trigger :componentWillUnmount})}
             "internals"]]
           [:div.filter-fields
            [:select {:value     @filter-type
                      :on-change #(reset! filter-type (keyword (.. % -target -value)))}
             [:option {:value "contains"} "contains"]
             [:option {:value "slower-than"} "slower than"]]
            [:div.filter-control-input {:style {:margin-left 10}}
             [search-input {:on-save   save-query
                            :on-change #(reset! filter-input (.. % -target -value))}]
             [components/icon-add]
             (if @input-error
               [:div.input-error {:style {:color "red" :margin-top 5}}
                "Please enter a valid number."])]]]
          [:ul.filter-items
           (map (fn [item]
                  ^{:key (:id item)}
                  [:li.filter-item
                   [:button.button
                    {:style    {:margin 0}
                     :on-click (fn [event] (swap! filter-items #(remove (comp (partial = (:query item)) :query) %)))}
                    (:filter-type item) ": " [:span.filter-item-string (:query item)]]])
                @filter-items)]]
         [components/autoscroll-list {:class "panel-content-scrollable" :scroll? true}
          [:table
           [:thead>tr
            [:th {:style {:padding 0}}
             [:button.text-button
              {:style    {:cursor "pointer"}
               :on-click (fn [ev]
                           ;; Always reset expansions
                           (swap! trace-detail-expansions assoc :overrides {})
                           ;; Then toggle :show-all?
                           (swap! trace-detail-expansions update :show-all? not))}
              (if (:show-all? @trace-detail-expansions) "-" "+")]]
            [:th "operations"]
            [:th
             [:button {:class    (str/join " " ["filter-items-count"
                                                (when (pos? (count @filter-items)) "active")])
                       :on-click #(reset! filter-items [])}
              (when (pos? (count @filter-items))
                (str (count visible-traces) " of "))
              (str (count @traces))]
             " events "
             (when (pos? (count @traces))
               [:span "(" [:button.text-button {:on-click #(do (trace/reset-tracing!) (reset! traces []))} "clear"] ")"])]
            [:th {:style {:text-align "right"}} "meta"]]
           [:tbody (render-traces visible-traces filter-items filter-input trace-detail-expansions)]]]]))))

(defn resizer-style [draggable-area]
  {:position "absolute" :z-index 2 :opacity 0
   :left     (str (- (/ draggable-area 2)) "px") :width "10px" :top "0px" :height "100%" :cursor "col-resize"})

(def ease-transition "left 0.2s ease-out, top 0.2s ease-out, width 0.2s ease-out, height 0.2s ease-out")

(defn toggle-traces [showing?]
  (if @showing?
    (enable-tracing!)
    (disable-tracing!)))

(defn devtools []
  ;; Add clear button
  ;; Filter out different trace types
  (let [position             (r/atom :right)
        panel-width%         (r/atom (localstorage/get "panel-width-ratio" 0.35))
        showing?             (r/atom (localstorage/get "show-panel" false))
        dragging?            (r/atom false)
        pin-to-bottom?       (r/atom true)
        selected-tab         (r/atom :traces)
        window-width         (r/atom js/window.innerWidth)
        handle-window-resize (fn [e]
                               ;; N.B. I don't think this should be a perf bottleneck.
                               (reset! window-width js/window.innerWidth))
        handle-keys          (fn [e]
                               (let [combo-key?      (or (.-ctrlKey e) (.-metaKey e) (.-altKey e))
                                     tag-name        (.-tagName (.-target e))
                                     key             (.-key e)
                                     entering-input? (contains? #{"INPUT" "SELECT" "TEXTAREA"} tag-name)]
                                 (when (and (not entering-input?) combo-key?)
                                   (cond
                                     (and (= key "h") (.-ctrlKey e))
                                     (do (swap! showing? not)
                                         (toggle-traces showing?)
                                         (.preventDefault e))))))
        handle-mousemove     (fn [e]
                               (when @dragging?
                                 (let [x                (.-clientX e)
                                       y                (.-clientY e)
                                       new-window-width js/window.innerWidth]
                                   (.preventDefault e)
                                   ;; Set a minimum width of 5% to prevent people from accidentally dragging it too small.
                                   (reset! panel-width% (max (/ (- new-window-width x) new-window-width) 0.05))
                                   (reset! window-width new-window-width))))
        handle-mouse-up      (fn [e] (reset! dragging? false))]
    (add-watch panel-width%
               :update-panel-width-ratio
               (fn [_ _ _ new-state]
                 (localstorage/save! "panel-width-ratio" new-state)))
    (add-watch showing?
               :update-show-panel
               (fn [_ _ _ new-state]
                 (localstorage/save! "show-panel" new-state)))
    (r/create-class
      {:component-will-mount   (fn []
                                 (toggle-traces showing?)
                                 (js/window.addEventListener "keydown" handle-keys)
                                 (js/window.addEventListener "mousemove" handle-mousemove)
                                 (js/window.addEventListener "mouseup" handle-mouse-up)
                                 (js/window.addEventListener "resize" handle-window-resize))
       :component-will-unmount (fn []
                                 (js/window.removeEventListener "keydown" handle-keys)
                                 (js/window.removeEventListener "mousemove" handle-mousemove)
                                 (js/window.removeEventListener "mouseup" handle-mouse-up)
                                 (js/window.removeEventListener "resize" handle-window-resize))
       :display-name           "devtools outer"
       :reagent-render         (fn []
                                 (let [draggable-area 10
                                       left           (if @showing? (str (* 100 (- 1 @panel-width%)) "%")
                                                                    (str @window-width "px"))
                                       transition     (if @dragging?
                                                        ""
                                                        ease-transition)]
                                   [:div.panel-wrapper
                                    {:style {:position "fixed" :width "0px" :height "0px" :top "0px" :left "0px" :z-index 99999999}}
                                    [:div.panel
                                     {:style {:position   "fixed" :z-index 1 :box-shadow "rgba(0, 0, 0, 0.3) 0px 0px 4px" :background "white"
                                              :left       left :top "0px" :width (str (inc (int (* 100 @panel-width%))) "%") :height "100%"
                                              :transition transition}}
                                     [:div.panel-resizer {:style         (resizer-style draggable-area)
                                                          :on-mouse-down #(reset! dragging? true)}]
                                     [:div.panel-content
                                      {:style {:width "100%" :height "100%" :display "flex" :flex-direction "column"}}
                                      [:div.panel-content-top
                                       [:div.nav
                                        [:button {:class    (str "tab button " (when (= @selected-tab :traces) "active"))
                                                  :on-click #(reset! selected-tab :traces)} "Traces"]
                                        [:button {:class    (str "tab button " (when (= @selected-tab :subvis) "active"))
                                                  :on-click #(reset! selected-tab :subvis)} "SubVis"]]]
                                      (case @selected-tab
                                        :traces [render-trace-panel]
                                        :subvis [subvis/render-subvis traces
                                                 [:div.panel-content-scrollable]])]]]))})))

(defn panel-div []
  (let [id    "--re-frame-trace--"
        panel (.getElementById js/document id)]
    (if panel
      panel
      (let [new-panel (.createElement js/document "div")]
        (.setAttribute new-panel "id" id)
        (.appendChild (.-body js/document) new-panel)
        (js/window.focus new-panel)
        new-panel))))

(defn inject-styles []
  (let [id            "--re-frame-trace-styles--"
        styles-el     (.getElementById js/document id)
        new-styles-el (.createElement js/document "style")
        new-styles    styles/panel-styles]
    (.setAttribute new-styles-el "id" id)
    (-> new-styles-el
        (.-innerHTML)
        (set! new-styles))
    (if styles-el
      (-> styles-el
          (.-parentNode)
          (.replaceChild new-styles-el styles-el))
      (let []
        (.appendChild (.-head js/document) new-styles-el)
        new-styles-el))))

(defn inject-devtools! []
  (inject-styles)
  (r/render [devtools] (panel-div)))
