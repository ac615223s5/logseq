(ns frontend.components.user.login
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [dommy.core :refer-macros [sel]]
            [frontend.config :as config]
            [frontend.handler.db-based.sync :as db-sync-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.route :as route-handler]
            [frontend.handler.user :as user]
            [frontend.modules.shortcut.core :as shortcut]
            [frontend.state :as state]
            [io.factorhouse.hsx.core :as hsx]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]))

(declare setupAuthConfigure! LSAuthenticator)

(defn sign-out!
  []
  (try (.signOut js/LSAuth.Auth)
       (catch :default e (js/console.warn e))))

(defn setup-configure!
  []
  #_:clj-kondo/ignore
  (defn setupAuthConfigure! [config]
    (.init js/LSAuth (bean/->js {:authCognito (merge config {:loginWith {:email true}})})))
  #_:clj-kondo/ignore
    (def LSAuthenticator
      (.-LSAuthenticator js/LSAuth))

  (setupAuthConfigure!
   {:region config/REGION,
    :userPoolId config/USER-POOL-ID,
    :userPoolClientId config/COGNITO-CLIENT-ID,
    :identityPoolId config/IDENTITY-POOL-ID,
    :oauthDomain config/OAUTH-DOMAIN}))

(defn authenticator
  [opts & children]
  (into [:> LSAuthenticator opts] children))

(hsx/defc user-pane
  [_sign-out! user]
  (let [session  (:signInUserSession user)]

    (hooks/use-effect!
     (fn []
       (when session
         (user/login-callback session)
         (shui/dialog-close!)
         (shui/popup-hide!)
         (when (= :user-login (state/get-current-route))
           (route-handler/redirect! {:to :home}))))
     [])

    nil))

(hsx/defc page-impl
  []
  [:div.cp__user-login
     (authenticator
      {:titleRender (fn [key title]
                      (shui/card-header
                       {:class "px-0"
                        :data-auth-title-key (str key)}
                       (shui/card-title
                        {:class "capitalize"}
                        (string/replace title "-" " "))))
       :onSessionCallback #()}
      (fn [^js op]
        (let [sign-out!' (.-signOut op)
              user' (bean/->clj (.-sessionUser op))]
          (user-pane sign-out!' user'))))])

;;; Account-less login: a single passphrase + self-hosted sync server URL.
;;; No username, no Logseq account. See frontend.common.sync-key.

(hsx/defc sync-key-form
  []
  (let [[server set-server!] (hooks/use-state (or (config/get-custom-sync-server-url)
                                                  "http://192.168.2.14:8787"))
        [passphrase set-passphrase!] (hooks/use-state "")
        [busy? set-busy!] (hooks/use-state false)
        submit!
        (fn []
          (let [server' (string/trim (or server ""))
                pass' (string/trim (or passphrase ""))]
            (cond
              (not (config/valid-sync-server-url? server'))
              (notification/show! "Enter a valid sync server URL (http:// or https://)." :warning)

              (< (count pass') 8)
              (notification/show! "Use a passphrase of at least 8 characters." :warning)

              :else
              (do
                (set-busy! true)
                (-> (db-sync-handler/<login-with-sync-key! pass' server')
                    (p/then (fn []
                              (shui/dialog-close!)
                              (shui/popup-hide!)
                              (when (= :user-login (state/get-current-route))
                                (route-handler/redirect! {:to :home}))))
                    (p/catch (fn [e]
                               (js/console.error :sync-key-login-failed e)
                               (notification/show! "Login failed. Check the server URL and try again." :error)))
                    (p/finally (fn [] (set-busy! false))))))))]
    [:div.cp__user-login.flex.flex-col.gap-4.p-1.pt-2
     [:div.flex.flex-col.gap-1
      [:h2.text-lg.font-medium "Connect to sync server"]
      [:p.text-sm.opacity-60
       "No account needed. Your passphrase is your identity and end-to-end encryption key — the same passphrase on any device syncs the same graphs. Keep it safe: it cannot be recovered."]]
     [:div.flex.flex-col.gap-1
      [:label.text-sm.opacity-70 {:for "sync-server-url"} "Sync server URL"]
      (shui/input
       {:id "sync-server-url"
        :value server
        :placeholder "http://192.168.2.14:8787"
        :on-change (fn [^js e] (set-server! (.. e -target -value)))})]
     [:div.flex.flex-col.gap-1
      [:label.text-sm.opacity-70 {:for "sync-passphrase"} "Passphrase"]
      (shui/input
       {:id "sync-passphrase"
        :type "password"
        :value passphrase
        :placeholder "Your sync passphrase"
        :autoFocus true
        :on-key-down (fn [^js e] (when (= "Enter" (.-key e)) (submit!)))
        :on-change (fn [^js e] (set-passphrase! (.. e -target -value)))})]
     (shui/button
      {:disabled busy?
       :on-click submit!}
      (if busy? "Connecting…" "Connect"))]))

(hsx/defc modal-inner
  []
  (shortcut/use-disable-all-shortcuts!)
  (sync-key-form))

(hsx/defc page
  []
  [:div.pt-10 (sync-key-form)])

(defn open-login-modal!
  []
  (shui/dialog-open!
   (fn [_close] (modal-inner))
   {:label :user-login}))
