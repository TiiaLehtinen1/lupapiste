(ns lupapalvelu.document.yleiset-alueet-canonical
  (:require [lupapalvelu.core :refer [now]]
            [lupapalvelu.document.canonical-common :refer :all]
            [lupapalvelu.document.tools :as tools]
            [sade.util :refer :all]
            [clojure.walk :as walk]
            [sade.common-reader :as cr]
            [cljts.geom :as geo]
            [cljts.io :as jts]))

(defn- get-henkilo [henkilo]
  (let [nimi (assoc-when {}
               :etunimi (-> henkilo :henkilotiedot :etunimi)
               :sukunimi (-> henkilo :henkilotiedot :sukunimi))
        teksti (assoc-when {} :teksti (-> henkilo :osoite :katu))
        osoite (assoc-when {}
                 :osoitenimi teksti
                 :postinumero (-> henkilo :osoite :postinumero)
                 :postitoimipaikannimi (-> henkilo :osoite :postitoimipaikannimi))]
    (not-empty
      (assoc-when {}
        :nimi nimi
        :osoite osoite
        :sahkopostiosoite (-> henkilo :yhteystiedot :email)
        :puhelin (-> henkilo :yhteystiedot :puhelin)
        :henkilotunnus (-> henkilo :henkilotiedot :hetu)))))

(defn- get-postiosoite [yritys]
  (let [teksti (assoc-when {} :teksti (-> yritys :osoite :katu))]
    (not-empty
      (assoc-when {}
        :osoitenimi teksti
        :postinumero (-> yritys :osoite :postinumero)
        :postitoimipaikannimi (-> yritys :osoite :postitoimipaikannimi)))))

(defn- get-yritys [yritys is-maksaja-doc]
  (let [postiosoite (get-postiosoite yritys)
        yritys-basic (not-empty
                       (assoc-when {}
                         :nimi (-> yritys :yritysnimi)
                         :liikeJaYhteisotunnus (-> yritys :liikeJaYhteisoTunnus)))]
    (if postiosoite
      (merge
        yritys-basic
        (if is-maksaja-doc
          {:postiosoite postiosoite}
          {:postiosoitetieto {:Postiosoite postiosoite}}))
      yritys-basic)))

(defn- get-hakija [hakija-doc]
  ;; Yritys-tyyppisella hakijalla tiedot jaetaan yritystietoon ja henkilotieto,
  ;; Henkilo-tyyppisella hakijalla kaikki kulkee henkilotiedon alla.
  (let [hakija (not-empty
                 (if (= "yritys" (:_selected hakija-doc))
                   (let [yritys (get-yritys (:yritys hakija-doc) false)
                         henkilo (get-henkilo (-> hakija-doc :yritys :yhteyshenkilo))]
                     (when (and yritys henkilo)
                       {:Osapuoli {:yritystieto {:Yritys yritys}
                                   :henkilotieto {:Henkilo henkilo}}}))
                   (when-let [henkilo (get-henkilo (:henkilo hakija-doc))]
                     {:Osapuoli {:henkilotieto {:Henkilo henkilo}}})))]
    (when hakija
      (update-in hakija [:Osapuoli] merge {:rooliKoodi "hakija"}))))

(defn- get-vastuuhenkilo-osoitetieto [osoite]
  (let [osoitenimi (assoc-when {} :teksti (-> osoite :katu))
        osoite (not-empty
                 (assoc-when {}
                   :osoitenimi osoitenimi
                   :postinumero (-> osoite :postinumero)
                   :postitoimipaikannimi (-> osoite :postitoimipaikannimi)))]
    (when osoite {:osoite osoite})))

(defn- get-vastuuhenkilo [vastuuhenkilo type roolikoodi]
  (let [content (not-empty
                  (if (= type "yritys")
                    ;; yritys-tyyppinen vastuuhenkilo
                    (assoc-when {}
                      :sukunimi (-> vastuuhenkilo :yritys :yhteyshenkilo :henkilotiedot :sukunimi)
                      :etunimi (-> vastuuhenkilo :yritys :yhteyshenkilo :henkilotiedot :etunimi)
                      :osoitetieto (get-vastuuhenkilo-osoitetieto (-> vastuuhenkilo :yritys :osoite))
                      :puhelinnumero (-> vastuuhenkilo :yritys :yhteyshenkilo :yhteystiedot :puhelin)
                      :sahkopostiosoite (-> vastuuhenkilo :yritys :yhteyshenkilo :yhteystiedot :email))
                    ;; henkilo-tyyppinen vastuuhenkilo
                    (assoc-when {}
                      :sukunimi (-> vastuuhenkilo :henkilo :henkilotiedot :sukunimi)
                      :etunimi (-> vastuuhenkilo :henkilo :henkilotiedot :etunimi)
                      :osoitetieto (get-vastuuhenkilo-osoitetieto (-> vastuuhenkilo :henkilo :osoite))
                      :puhelinnumero (-> vastuuhenkilo :henkilo :yhteystiedot :puhelin)
                      :sahkopostiosoite (-> vastuuhenkilo :henkilo :yhteystiedot :email))))]
    (when content
      (merge content {:rooliKoodi roolikoodi}))))

(defn- get-tyomaasta-vastaava [tyomaasta-vastaava]
  (let [type (-> tyomaasta-vastaava :_selected)
        vastuuhenkilo (get-vastuuhenkilo tyomaasta-vastaava type "lupaehdoista/ty\u00f6maasta vastaava henkil\u00f6")]
    (when vastuuhenkilo
      (merge
        {:Vastuuhenkilo vastuuhenkilo}
        (when (= "yritys" type)
          (when-let [yritys (get-yritys (:yritys tyomaasta-vastaava) false)]
            {:Osapuoli {:yritystieto {:Yritys yritys}
                        :rooliKoodi "ty\u00f6nsuorittaja"}}))))))

(defn- get-yritys-and-henkilo [doc doc-type]
  (let [is-maksaja-doc (true? (= "maksaja" doc-type))
        info (if (= (-> doc :_selected) "yritys")
               ;; yritys-tyyppinen hakija/maksaja, siirretaan yritysosa omaksi osapuolekseen
               (let [vastuuhenkilo-roolikoodi (if is-maksaja-doc "maksajan vastuuhenkil\u00f6" "hankkeen vastuuhenkil\u00f6")
                     vastuuhenkilo (get-vastuuhenkilo doc "yritys" vastuuhenkilo-roolikoodi)
                     yritys (get-yritys (:yritys doc) is-maksaja-doc)]
                 (when (and vastuuhenkilo yritys)
                   {:Vastuuhenkilo vastuuhenkilo
                    :Osapuoli {:yritystieto {:Yritys yritys}}}))
               ;; henkilo-tyyppinen hakija/maksaja
               (when-let [henkilo (get-henkilo (:henkilo doc))]
                 {:Osapuoli {:henkilotieto {:Henkilo henkilo}}}))]
    (when info
      (update-in info [:Osapuoli] merge (if is-maksaja-doc
                                          {:laskuviite (-> doc :laskuviite)}
                                          {:rooliKoodi "hakija"})))))

(defn- get-handler [application]
  (let [handler (:authority application)]
    (if (seq handler)
      {:henkilotieto {:Henkilo {:nimi {:etunimi  (:firstName handler)
                                       :sukunimi (:lastName handler)}}}}
      empty-tag)))

(defn- get-kasittelytieto [application]
  {:Kasittelytieto {:muutosHetki (to-xml-datetime (:modified application))
                    :hakemuksenTila (application-state-to-krysp-state (keyword (:state application)))
                    :asiatunnus (:id application)
                    :paivaysPvm (to-xml-date (application (state-timestamps (keyword (:state application)))))
                    :kasittelija (get-handler application)}})


(defn- get-pos [coordinates]
  {:pos (map #(str (-> % .x) " " (-> % .y)) coordinates)})

(defn- point-drawing [drawing]
  (let  [geometry (:geometry drawing)
         p (jts/read-wkt-str geometry)
         cord (.getCoordinate p)]
    {:Sijainti
     {:piste {:Point {:pos (str (-> cord .x) " " (-> cord .y))}}}}))

(defn- linestring-drawing [drawing]
  (let  [geometry (:geometry drawing)
         ls (jts/read-wkt-str geometry)]
    {:Sijainti
     {:viiva {:LineString (get-pos (-> ls .getCoordinates))}}}))

(defn- polygon-drawing [drawing]
  (let  [geometry (:geometry drawing)
         polygon (jts/read-wkt-str geometry)]
    {:Sijainti
     {:alue {:Polygon {:exterior {:LinearRing (get-pos (-> polygon .getCoordinates))}}}}}))

(defn- drawing-type? [t drawing]
  (.startsWith (:geometry drawing) t))

(defn- drawings-as-krysp [drawings]
   (concat (map point-drawing (filter (partial drawing-type? "POINT") drawings))
           (map linestring-drawing (filter (partial drawing-type? "LINESTRING") drawings))
           (map polygon-drawing (filter (partial drawing-type? "POLYGON") drawings))))


(defn- get-sijaintitieto [application]
  (let [drawings (drawings-as-krysp (:drawings application))]
    (cons {:Sijainti {:osoite {:yksilointitieto (:id application)
                               :alkuHetki (to-xml-datetime (now))
                               :osoitenimi {:teksti (:address application)}}
                      :piste {:Point {:pos (str (:x (:location application)) " " (:y (:location application)))}}}}
      drawings)))

(defn- get-lisatietoja-sijoituskohteesta [data]
  (when-let [arvo (-> data :lisatietoja-sijoituskohteesta)]
    {:selitysteksti "Lis\u00e4tietoja sijoituskohteesta" :arvo arvo}))

(defn- get-sijoituksen-tarkoitus [data]
  (when-let [arvo (if (= "other" (:sijoituksen-tarkoitus data))
                    (-> data :muu-sijoituksen-tarkoitus)
                    (-> data :sijoituksen-tarkoitus))]
    {:selitysteksti "Sijoituksen tarkoitus" :arvo arvo}))

(defn- get-mainostus-alku-loppu-hetki [mainostus-viitoitus-tapahtuma]
  {:Toimintajakso {:alkuHetki (to-xml-datetime-from-string (-> mainostus-viitoitus-tapahtuma :mainostus-alkaa-pvm))
                   :loppuHetki (to-xml-datetime-from-string (-> mainostus-viitoitus-tapahtuma :mainostus-paattyy-pvm))}})

(defn- get-mainostus-viitoitus-lisatiedot [mainostus-viitoitus-tapahtuma]
  [{:LupakohtainenLisatieto
    (when-let [arvo (-> mainostus-viitoitus-tapahtuma :tapahtuman-nimi)]
      {:selitysteksti "Tapahtuman nimi" :arvo arvo})}
   {:LupakohtainenLisatieto
    (when-let [arvo (-> mainostus-viitoitus-tapahtuma :tapahtumapaikka)]
      {:selitysteksti "Tapahtumapaikka" :arvo arvo})}
   {:LupakohtainenLisatieto
    (when-let [arvo (-> mainostus-viitoitus-tapahtuma :haetaan-kausilupaa)]
      {:selitysteksti "Haetaan kausilupaa" :arvo arvo})}])

(defn- get-construction-ready-info [application]
  {:kayttojaksotieto {:Kayttojakso {:alkuHetki (to-xml-datetime (:started application))
                                    :loppuHetki (to-xml-datetime (:closed application))}}
   :valmistumisilmoitusPvm (to-xml-date (now))})


;; Configs

(def ^:private default-config {:hankkeen-kuvaus                                true
                               :tyoaika                                        true})

(def ^:private kayttolupa-config-plus-tyomaastavastaava
  (merge default-config {:tyomaasta-vastaava                                   true}))

(def ^:private configs-per-permit-name
  {:Kayttolupa                  default-config

   :Tyolupa                     (merge default-config
                                  {:sijoitus-lisatiedot                        true
                                   :tyomaasta-vastaava                         true
                                   :hankkeen-kuvaus-with-sijoituksen-tarkoitus true
                                   :johtoselvitysviitetieto                    true})


   :Sijoituslupa                (merge default-config
                                  {:tyoaika                                    false
                                   :dummy-alku-and-loppu-pvm                   true
                                   :sijoitus-lisatiedot                        true})

   :ya-kayttolupa-nostotyot               kayttolupa-config-plus-tyomaastavastaava
   :ya-kayttolupa-vaihtolavat             kayttolupa-config-plus-tyomaastavastaava
   :ya-kayttolupa-kattolumien-pudotustyot kayttolupa-config-plus-tyomaastavastaava
   :ya-kayttolupa-muu-liikennealuetyo     kayttolupa-config-plus-tyomaastavastaava
   :ya-kayttolupa-talon-julkisivutyot     kayttolupa-config-plus-tyomaastavastaava
   :ya-kayttolupa-talon-rakennustyot      kayttolupa-config-plus-tyomaastavastaava
   :ya-kayttolupa-muu-tyomaakaytto        kayttolupa-config-plus-tyomaastavastaava

   :ya-kayttolupa-mainostus-ja-viitoitus {:mainostus-viitoitus-tapahtuma-pvm   true
                                          :mainostus-viitoitus-lisatiedot      true}})


(defn- permits [application]
  ;;
  ;; Sijoituslupa: Maksaja, alkuPvm and loppuPvm are not filled in the application, but are requested by schema
  ;;               -> Maksaja gets Hakija's henkilotieto, AlkuPvm/LoppuPvm both get application's "modified" date.
  ;;
  (let [application (tools/unwrapped application)
        documents-by-type (documents-by-type-without-blanks application)

        link-permit-data (-> application :linkPermitData first)

        operation-name-key (-> application :operations first :name keyword)
        permit-name-key (ya-operation-type-to-schema-name-key operation-name-key)

        config (or (configs-per-permit-name operation-name-key) (configs-per-permit-name permit-name-key))

;        hakija (get-yritys-and-henkilo (-> documents-by-type :hakija-ya first :data) "hakija")
        hakija (get-hakija (-> documents-by-type :hakija-ya first :data))

        tyoaika-doc (when (:tyoaika config)
                      (-> documents-by-type :tyoaika first :data))

        main-viit-tapahtuma-doc (-> documents-by-type :mainosten-tai-viitoitusten-sijoittaminen first :data)
        ;; If user has manually selected the mainostus/viitoitus type, the _selected key exists.
        ;; Otherwise the type is the first key in the map.
        main-viit-tapahtuma-name (when main-viit-tapahtuma-doc
                                   (or
                                     (-> main-viit-tapahtuma-doc :_selected keyword)
                                     (-> main-viit-tapahtuma-doc first key)))
        main-viit-tapahtuma (when main-viit-tapahtuma-doc
                             (main-viit-tapahtuma-doc main-viit-tapahtuma-name))

        alku-pvm (if (:dummy-alku-and-loppu-pvm config)
                   (to-xml-date (:submitted application))
                   (if (:mainostus-viitoitus-tapahtuma-pvm config)
                     (to-xml-date-from-string (-> main-viit-tapahtuma :tapahtuma-aika-alkaa-pvm))
                     (to-xml-date-from-string (-> tyoaika-doc :tyoaika-alkaa-pvm))))
        loppu-pvm (if (:dummy-alku-and-loppu-pvm config)
                    (to-xml-date (:modified application))
                    (if (:mainostus-viitoitus-tapahtuma-pvm config)
                      (to-xml-date-from-string (-> main-viit-tapahtuma :tapahtuma-aika-paattyy-pvm))
                      (to-xml-date-from-string (-> tyoaika-doc :tyoaika-paattyy-pvm))))
        maksaja (if (:dummy-maksaja config)
                  (merge (:Osapuoli hakija) {:laskuviite "0000000000"})
                  (get-yritys-and-henkilo (-> documents-by-type :yleiset-alueet-maksaja first :data) "maksaja"))
        maksajatieto (when maksaja {:Maksaja (:Osapuoli maksaja)})
        tyomaasta-vastaava (when (:tyomaasta-vastaava config)
                             (get-tyomaasta-vastaava (-> documents-by-type :tyomaastaVastaava first :data)))
        ;; If tyomaasta-vastaava does not have :osapuolitieto, we filter the resulting nil out.
        osapuolitieto (vec (filter :Osapuoli [hakija
                                              tyomaasta-vastaava]))
        ;; If tyomaasta-vastaava does not have :vastuuhenkilotieto, we filter the resulting nil out.
        vastuuhenkilotieto (when (or (:tyomaasta-vastaava config) (not (:dummy-maksaja config)))
                             (vec (filter :Vastuuhenkilo [;hakija
                                                          tyomaasta-vastaava
                                                          maksaja])))
        hankkeen-kuvaus (when (:hankkeen-kuvaus config)
                          (->
                            (or
                              (:yleiset-alueet-hankkeen-kuvaus-sijoituslupa documents-by-type)
                              (:yleiset-alueet-hankkeen-kuvaus-kayttolupa documents-by-type)
                              (:yleiset-alueet-hankkeen-kuvaus-kaivulupa documents-by-type))
                            first :data))

        lupaAsianKuvaus (when (:hankkeen-kuvaus config)
                          (-> hankkeen-kuvaus :kayttotarkoitus))

        pinta-ala (when (:hankkeen-kuvaus config)
                    (-> hankkeen-kuvaus :varattava-pinta-ala))

        lupakohtainenLisatietotieto (filter #(seq (:LupakohtainenLisatieto %))
                                      (flatten
                                        (vector
                                          (when-let [erikoiskuvaus-operaatiosta (ya-operation-type-to-additional-usage-description operation-name-key)]
                                            {:LupakohtainenLisatieto {:selitysteksti "Lis\u00e4tietoja k\u00e4ytt\u00f6tarkoituksesta"
                                                                      :arvo erikoiskuvaus-operaatiosta}})
                                          (when (:sijoitus-lisatiedot config)
                                            (if (:hankkeen-kuvaus-with-sijoituksen-tarkoitus config)
                                              (let [sijoituksen-tarkoitus-doc (-> documents-by-type :yleiset-alueet-hankkeen-kuvaus-kaivulupa first :data)]
                                                [{:LupakohtainenLisatieto (get-sijoituksen-tarkoitus sijoituksen-tarkoitus-doc)}])
                                              (let [sijoituksen-tarkoitus-doc (-> documents-by-type :sijoituslupa-sijoituksen-tarkoitus first :data)]
                                                [{:LupakohtainenLisatieto (get-sijoituksen-tarkoitus sijoituksen-tarkoitus-doc)}
                                                 {:LupakohtainenLisatieto (get-lisatietoja-sijoituskohteesta sijoituksen-tarkoitus-doc)}])))
                                          (when (:mainostus-viitoitus-lisatiedot config)
                                            (get-mainostus-viitoitus-lisatiedot main-viit-tapahtuma)))))

        sijoituslupaviitetieto (when (:hankkeen-kuvaus config)
                                 (when-let [tunniste (-> hankkeen-kuvaus :sijoitusLuvanTunniste)]
                                   {:Sijoituslupaviite {:vaadittuKytkin false
                                                        :tunniste tunniste}}))

        johtoselvitysviitetieto (when (:johtoselvitysviitetieto config)
                                  {:Johtoselvitysviite {:vaadittuKytkin false
                                                        ;:tunniste "..."
                                                        }})

        body {permit-name-key (merge
                                {:kasittelytietotieto (get-kasittelytieto application)
                                 :luvanTunnisteTiedot (get-viitelupatieto link-permit-data)
                                 :alkuPvm alku-pvm
                                 :loppuPvm loppu-pvm
                                 :sijaintitieto (get-sijaintitieto application)
                                 :pintaala pinta-ala
                                 :osapuolitieto osapuolitieto
                                 :vastuuhenkilotieto vastuuhenkilotieto
                                 :maksajatieto maksajatieto
                                 :lausuntotieto (get-statements (:statements application))
                                 :lupaAsianKuvaus lupaAsianKuvaus
                                 :lupakohtainenLisatietotieto lupakohtainenLisatietotieto
                                 :sijoituslupaviitetieto sijoituslupaviitetieto
                                 :kayttotarkoitus (ya-operation-type-to-usage-description operation-name-key)
                                 :johtoselvitysviitetieto johtoselvitysviitetieto}
                                (when (= "mainostus-tapahtuma-valinta" (name main-viit-tapahtuma-name))
                                  {:toimintajaksotieto (get-mainostus-alku-loppu-hetki main-viit-tapahtuma)})
                                (when (:closed application)
                                  (get-construction-ready-info application)))}]
    (cr/strip-nils body)))

(defn application-to-canonical
  "Transforms application mongodb-document to canonical model."
  [application lang]
  {:YleisetAlueet {:toimituksenTiedot (toimituksen-tiedot application lang)
                   :yleinenAlueAsiatieto (permits application)}})


(defn jatkoaika-to-canonical [application lang]
  "Transforms continuation period application mongodb-document to canonical model."
  [application lang]
  (let [application (tools/unwrapped application)
        documents-by-type (documents-by-type-without-blanks application)

        link-permit-data (-> application :linkPermitData first)

        ;; When operation is missing, setting kaivulupa as the operation (app created via op tree)
        operation-name-key (or (-> link-permit-data :operation keyword) :ya-katulupa-vesi-ja-viemarityot)
        permit-name-key (ya-operation-type-to-schema-name-key operation-name-key)

        config (or (configs-per-permit-name operation-name-key) (configs-per-permit-name permit-name-key))

;        hakija (get-yritys-and-henkilo (-> documents-by-type :hakija-ya first :data) "hakija")
        hakija (get-hakija (-> documents-by-type :hakija-ya first :data))

        tyoaika-doc (-> documents-by-type :tyo-aika-for-jatkoaika first :data)
        alku-pvm (if-let [tyoaika-alkaa-value (-> tyoaika-doc :tyoaika-alkaa-pvm)]
                   (to-xml-date-from-string tyoaika-alkaa-value)
                   (to-xml-date (:submitted application)))
        loppu-pvm (to-xml-date-from-string (-> tyoaika-doc :tyoaika-paattyy-pvm))
        maksaja (get-yritys-and-henkilo (-> documents-by-type :yleiset-alueet-maksaja first :data) "maksaja")
        maksajatieto (when maksaja {:Maksaja (:Osapuoli maksaja)})
        osapuolitieto (vec (filter :Osapuoli [hakija]))
        vastuuhenkilotieto (vec (filter :Vastuuhenkilo [;hakija
                                                        maksaja]))
        hankkeen-kuvaus (-> documents-by-type :hankkeen-kuvaus-jatkoaika first :data :kuvaus)
        lisaaikatieto (when alku-pvm loppu-pvm hankkeen-kuvaus
                        {:Lisaaika {:alkuPvm alku-pvm
                                    :loppuPvm loppu-pvm
                                    :perustelu hankkeen-kuvaus}})
        johtoselvitysviitetieto (when (:johtoselvitysviitetieto config)
                                  {:Johtoselvitysviite {:vaadittuKytkin false
                                                        ;:tunniste "..."
                                                        }})]
    {:YleisetAlueet
     {:toimituksenTiedot (toimituksen-tiedot application lang)
      :yleinenAlueAsiatieto {permit-name-key
                             {:kasittelytietotieto (get-kasittelytieto application)
                              :luvanTunnisteTiedot (get-viitelupatieto link-permit-data)
                              :alkuPvm alku-pvm
                              :loppuPvm loppu-pvm
                              :sijaintitieto (get-sijaintitieto application)
                              :osapuolitieto osapuolitieto
                              :vastuuhenkilotieto vastuuhenkilotieto
                              :maksajatieto maksajatieto
                              :lisaaikatieto lisaaikatieto
                              :kayttotarkoitus (ya-operation-type-to-usage-description operation-name-key)
                              :johtoselvitysviitetieto johtoselvitysviitetieto
                              }}}}))


