(ns lupapalvelu.document.poikkeamis-canonical
  (:require [lupapalvelu.document.canonical-common :refer :all]
            [lupapalvelu.document.tools :as tools]
            [sade.strings :as ss]
            [sade.util :as util]))

(defn- root-element [application lang]
  {:Popast
   {:toimituksenTiedot (toimituksen-tiedot application lang)}})

(defn- get-toimenpide [{toimenpide :toimenpiteet} common]
  (merge common {:kuvausKoodi (:Toimenpide toimenpide)
                 :tavoitetilatieto {:Tavoitetila {:paakayttotarkoitusKoodi (:kayttotarkoitus toimenpide)
                                                  :asuinhuoneitojenLkm (:huoneistoja toimenpide)
                                                  :rakennuksenKerrosluku (:kerroksia toimenpide)
                                                  :kokonaisala (:kokonaisala toimenpide)
                                                  ; 2.1.3+
                                                  :kerrosala (:kerrosala toimenpide)
                                                  ; 2.1.2
                                                  :kerrosalatieto {:kerrosala {:pintaAla (:kerrosala toimenpide)
                                                                               :paakayttotarkoitusKoodi (:kayttotarkoitus toimenpide)}}}}}))

(defn- get-toimenpidefull [{{toimenpiteet :toimenpiteet kaytettykerrosala :kaytettykerrosala} :data :as toimenpide}]
  (let [kaytettykerrosala-canonical (when-not (ss/blank? (:pintaAla kaytettykerrosala))
                                      {:kerrosalatieto {:kerrosala {:pintaAla (:pintaAla kaytettykerrosala)
                                                                    :paakayttotarkoitusKoodi (:kayttotarkoitusKoodi kaytettykerrosala)}}})]
      {:Toimenpide (get-toimenpide (:data toimenpide) kaytettykerrosala-canonical)}))


(defn- get-toimenpiteet [toimenpiteet]
  (remove
    #(or (nil? %) (empty? %))
    (map (comp util/strip-empty-maps
               util/strip-nils
               get-toimenpidefull) toimenpiteet)))


(defn common-poikkeamis-asia [application poikkeamisasia-path lang kuvaus-avain kayttotapaus]
  (let [application (tools/unwrapped application)
        root (root-element application lang)
        documents-by-type (documents-by-type-without-blanks application)
        lisatiedot (:data (first (:lisatiedot documents-by-type)))
        hanke (:data (first (:hankkeen-kuvaus documents-by-type)))]
    (assoc-in
      root
      poikkeamisasia-path
      {:kasittelynTilatieto (get-state application)
       :kuntakoodi (:municipality application)
       :luvanTunnistetiedot (lupatunnus application)
       :osapuolettieto (osapuolet application documents-by-type lang)
       :rakennuspaikkatieto (get-bulding-places (:poikkeusasian-rakennuspaikka documents-by-type) application)
       :toimenpidetieto (get-toimenpiteet (:rakennushanke documents-by-type))
       :lausuntotieto (get-statements (:statements application))
       :lisatietotieto {:Lisatieto {:asioimiskieli (if (= lang "se")
                                                     "ruotsi"
                                                     "suomi")}}
       :kayttotapaus kayttotapaus
       :asianTiedot {:Asiantiedot {:vahainenPoikkeaminen (:poikkeamat hanke)
                                   kuvaus-avain (:kuvaus hanke)}}})))

(defmulti poikkeus-application-to-canonical (fn [application lang] (:permitSubtype application)))

(defmethod poikkeus-application-to-canonical "poikkeamislupa" [application lang]
  (common-poikkeamis-asia application [:Popast :poikkeamisasiatieto :Poikkeamisasia] lang :poikkeamisasianKuvaus "Uusi poikkeamisasia"))

(defmethod poikkeus-application-to-canonical "suunnittelutarveratkaisu" [application lang]
  (common-poikkeamis-asia application [:Popast :suunnittelutarveasiatieto :Suunnittelutarveasia] lang :suunnittelutarveasianKuvaus "Uusi suunnittelutarveasia"))



