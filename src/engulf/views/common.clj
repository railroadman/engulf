(ns engulf.views.common
  (:use [noir.core :only [defpartial]]
        [hiccup.page-helpers :only [include-css html5 include-js javascript-tag link-to]]))

(defpartial layout [& content]
  (html5
    [:html {:class "no-js" :lang "en"}
    [:head [:meta {:charset "utf-8"}]
     [:title "Engulf HTTP Benchmarker"]
     [:meta {:name "description", :content "engulf http benchmarker"}]

     [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]

     (include-css
       "http://fonts.googleapis.com/css?family=Inconsolata"
       "/css/style.css"
       "/css/main.css")
     (include-js
       "/js/vendor/modernizr-2.0.min.js"
       "/js/vendor/respond.min.js"
       "/js/vendor/jquery.min.js"
       "/js/vendor/jquery-ui.min.js"
       "/js/vendor/d3/d3.js"
       "/js/vendor/d3/d3.chart.js"
       "/js/vendor/respond.min.js"
       "/js/vendor/script.js"
       "/js/vendor/jquery-ui.min.js",
       "/js/vendor/underscore.min.js",
       "/js/vendor/backbone.min.js",
       "/js/main.js")
     (javascript-tag "try{Typekit.load();}catch(e){};")]
    [:body [:div {:id "container"}
             [:header
               [:h1
                "(engulf)"]]
            [:div {:id "main" :role "main"}
             [:div {:id "firefox-warning" :style "display:none"}
              "Warning! Firefox runs these visualizations slowly. Please use Google Chrome for best results"]
             content]

           ]
     ]]))
