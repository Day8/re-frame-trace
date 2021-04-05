(ns day8.re-frame-10x.view.epochs
  (:require
    [day8.re-frame-10x.inlined-deps.reagent.v1v0v0.reagent.core :as r]
    [day8.re-frame-10x.inlined-deps.re-frame.v1v1v2.re-frame.core :as rf]
    [day8.re-frame-10x.inlined-deps.garden.v1v3v10.garden.units :as units :refer [em px percent]]
    [day8.re-frame-10x.inlined-deps.spade.v1v1v0.spade.core :refer [defclass defglobal]]
    [day8.re-frame-10x.utils.re-com :as rc]
    [day8.re-frame-10x.view.components :as components]
    [day8.re-frame-10x.material :as material]
    [day8.re-frame-10x.styles :as styles]
    [day8.re-frame-10x.styles :as styles]
    [day8.re-frame-10x.view.cljs-devtools :as cljs-devtools]))

(defclass epoch-style
  [ambiance active?]
  {:cursor (if (not active?) :pointer :default)}
  [:svg :path
   {:fill (if active? :#fff styles/nord4)}]
  [:&:hover
   [:svg :path
    {:fill (if active? :#fff :#fff)}]])

(defclass epoch-data-style
  [ambiance]
  {:background-color (if (= ambiance :bright) :#fff styles/nord0)})

(defn epoch
  []
  (let [hover?     (r/atom false)]
    (fn [event id]
      (let [ambiance   @(rf/subscribe [:settings/ambiance])
            current-id @(rf/subscribe [:epochs/current-epoch-id])
            active?    (= id current-id)]
        [rc/h-box
         :class    (epoch-style ambiance active?)
         :align    :center
         :height   styles/gs-19s
         :attr     {:on-click       #(when-not active? (rf/dispatch [:epochs/load-epoch id]))
                    :on-mouse-enter #(reset! hover? true)
                    :on-mouse-leave #(reset! hover? false)}
         :children [(if (or active? @hover?)
                      [rc/box
                       :height styles/gs-19s
                       :align :center
                       :style {:background-color styles/nord13}
                       :child [material/chevron-right
                               {:size "17px"}]]
                      [material/chevron-right
                       {:size "17px"}])
                    [rc/gap-f :size styles/gs-2s]
                    [rc/box
                     :class  (epoch-data-style ambiance)
                     :height styles/gs-19s
                     :size  "1"
                     :child [cljs-devtools/simple-render event []]]]]))))

(defclass epochs-style
  [ambiance]
  {:composes     (styles/background ambiance)
   :overflow-y   :auto
   #_#_:padding-left styles/gs-2s})

(defn epochs
  []
  (let [ambiance   @(rf/subscribe [:settings/ambiance])
        all-events @(rf/subscribe [:epochs/all-events-by-id])]
    [rc/v-box
     :class    (epochs-style ambiance)
     :height   styles/gs-131s
     :children (into [[rc/gap-f :size styles/gs-2s]]
                     (for [[id event] (reverse all-events)
                           :when (not-empty event)]
                       [:<>
                        [epoch event id]
                        [rc/gap-f :size styles/gs-2s]]))]))



(defn prev-button
  []
  (let [older-epochs-available? @(rf/subscribe [:epochs/older-epochs-available?])]
    [components/icon-button
     {:icon      [material/arrow-left]
      :title     (if older-epochs-available? "Previous epoch" "There are no previous epochs")
      :disabled? (not older-epochs-available?)
      :on-click  #(do (rf/dispatch [:component/set-direction :previous])
                      (rf/dispatch [:epochs/previous-epoch]))}]))

(defn next-button
  []
  (let [newer-epochs-available? @(rf/subscribe [:epochs/newer-epochs-available?])]
    [components/icon-button
     {:icon      [material/arrow-right]
      :title     (if newer-epochs-available? "Next epoch" "There are no later epochs")
      :disabled? (not newer-epochs-available?)
      :on-click  #(do (rf/dispatch [:component/set-direction :next])
                      (rf/dispatch [:epochs/next-epoch]))}]))

(defn latest-button
  []
  (let [newer-epochs-available? @(rf/subscribe [:epochs/newer-epochs-available?])]
    [components/icon-button
     {:icon      [material/skip-next]
      :title     (if newer-epochs-available? "Skip to latest epoch" "Already showig latest epoch")
      :disabled? (not newer-epochs-available?)
      :on-click  #(do (rf/dispatch [:component/set-direction :next])
                      (rf/dispatch [:epochs/most-recent-epoch]))}]))

(defn left-buttons
  []
  [rc/h-box
   :size     "1"
   :gap      styles/gs-12s
   :align    :center
   :children [[prev-button]
              [next-button]
              [latest-button]]])

(defn settings-button
  []
  [components/icon-button
   {:icon     [material/settings]
    :title    "Settings"
    :on-click #(rf/dispatch [:settings/toggle-settings])}])



(defn ambiance-button
  []
  (let [ambiance @(rf/subscribe [:settings/ambiance])]
    [components/icon-button
     {:icon (if (= ambiance :bright)
              [material/light-mode]
              [material/dark-mode])
      :title (if (= ambiance :bright)
               "Dark ambiance"
               "Bright ambiance")
      :on-click #(rf/dispatch [:settings/set-ambiance (if (= ambiance :bright) :dark :bright)])}]))

(defn right-buttons
  [external-window?]
  [rc/h-box
   :gap      styles/gs-12s
   :style    {:margin-right styles/gs-19s}
   :children [[ambiance-button]
              [settings-button]
              [components/popout-button external-window?]]])

(defclass navigation-style
  [ambiance]
  {:composes (styles/navigation-border-bottom ambiance)}
  [:.rc-label
   {:padding-left styles/gs-19s}])

(defn navigation
  [external-window?]
  (let [ambiance @(rf/subscribe [:settings/ambiance])]
    [rc/v-box
     :children [[rc/h-box
                 :class    (navigation-style ambiance)
                 :align    :center
                 :height   styles/gs-31s
                 :gap      styles/gs-19s
                 :children [[rc/label :label "Event History"]
                            [left-buttons]
                            [right-buttons external-window?]]]
                [epochs]]]))
