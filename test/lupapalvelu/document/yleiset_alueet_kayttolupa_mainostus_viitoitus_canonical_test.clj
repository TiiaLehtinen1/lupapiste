(ns lupapalvelu.document.yleiset-alueet-kayttolupa-mainostus-viitoitus-canonical-test
  (:require [lupapalvelu.document.yleiset-alueet-canonical-test-common :refer :all]
            [lupapalvelu.factlet :refer :all]
            [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [lupapalvelu.document.canonical-common :refer :all]
            [lupapalvelu.document.yleiset-alueet-canonical :refer [application-to-canonical]]
            [lupapalvelu.document.tools :as tools]
            [sade.util :refer :all]))


(def ^:private operation {:id "523fea5694a7732d5096f25d",
                          :created 1379920470831,
                          :name "ya-kayttolupa-mainostus-ja-viitoitus"})

(def ^:private tapahtuma-info
  {:id "523fea5694a7732d5096f25e",
   :created 1379920470831,
   :schema-info {:order 64,
                 :type "group",
                 :version 1,
                 :repeating false,
                 :removable false,
                 :op operation,
                 :name "mainosten-tai-viitoitusten-sijoittaminen"},
   :data {:_selected {:value "mainostus-tapahtuma-valinta"},
          :mainostus-tapahtuma-valinta {:mainostus-alkaa-pvm {:value "24.09.2013"},
                                        :mainostus-paattyy-pvm {:value "29.09.2013"},
                                        :tapahtuma-aika-alkaa-pvm {:value "27.09.2013"},
                                        :tapahtuma-aika-paattyy-pvm {:value "29.09.2013"},
                                        :tapahtuman-nimi {:value "Mainostettavan tapahtuman nimi"},
                                        :tapahtumapaikka {:value "Sipoon urheilukentt\u00e4"}
                                        :haetaan-kausilupaa {:value true}},
          :viitoitus-tapahtuma-valinta {:tapahtuma-aika-alkaa-pvm {:value "27.09.2013"},
                                        :tapahtuma-aika-paattyy-pvm {:value "29.09.2013"},
                                        :tapahtuman-nimi {:value "Viitoitettavan tapahtuman nimi"},
                                        :tapahtumapaikka {:value "Sipoon urheilukentt\u00e4"}}}})

(def ^:private documents [hakija
                          maksaja
                          tapahtuma-info])

(def mainostus-application
  {:schema-version 1,
   :id "LP-753-2013-00004",
   :created 1379920470831,
   :opened 1379920714420,
   :modified 1379920824001,
   :submitted 1379920746800,
   :permitType "YA",
   :organization "753-YA",
   :infoRequest false,
   :openInfoRequest false,
   :authority sonja,
   :state "submitted",
   :title "Latokuja 1",
   :address "Latokuja 1",
   :location location,
   :attachments [],
   :operations [operation],
   :propertyId "75341600550007",
   :documents documents,
   :municipality municipality,
   :statements statements})

(def viitoitus-application (assoc
                             (assoc mainostus-application :documents
                               [hakija
                                maksaja
                                (assoc-in tapahtuma-info [:data :_selected :value] "viitoitus-tapahtuma-valinta")])
                             :id "LP-753-2013-00005"))

(testable-privates lupapalvelu.document.yleiset-alueet-canonical get-yritys-and-henkilo)

(facts* "Mainostus-viitoituslupa canonical model is correct"
  (let [canonical (application-to-canonical mainostus-application "fi")
        YleisetAlueet (:YleisetAlueet canonical) => truthy
        yleinenAlueAsiatieto (:yleinenAlueAsiatieto YleisetAlueet) => truthy
        Mainostus-viitoituslupa (:Kayttolupa yleinenAlueAsiatieto) => truthy

        Kasittelytieto (-> Mainostus-viitoituslupa :kasittelytietotieto :Kasittelytieto) => truthy
        Kasittelytieto-kasittelija-nimi (-> Kasittelytieto :kasittelija :henkilotieto :Henkilo :nimi) => truthy

        luvanTunnisteTiedot (:luvanTunnisteTiedot Mainostus-viitoituslupa) => nil?

        Mainostus-viitoituslupa-kayttotarkoitus (:kayttotarkoitus Mainostus-viitoituslupa) => truthy

        Sijainti-osoite (-> Mainostus-viitoituslupa :sijaintitieto first :Sijainti :osoite) => truthy
        Sijainti-yksilointitieto (-> Sijainti-osoite :yksilointitieto) => truthy
        Sijainti-alkuHetki (-> Sijainti-osoite :alkuHetki) => truthy
        Sijainti-osoitenimi (-> Sijainti-osoite :osoitenimi :teksti) => truthy
        Sijainti-piste (-> Mainostus-viitoituslupa :sijaintitieto first :Sijainti :piste :Point :pos) => truthy

        vastuuhenkilot-vec (-> Mainostus-viitoituslupa :vastuuhenkilotieto) => truthy

        ;; maksajan yritystieto-osa
        Maksaja (-> Mainostus-viitoituslupa :maksajatieto :Maksaja) => truthy
        maksaja-Yritys (-> Maksaja :yritystieto :Yritys) => truthy
        maksaja-Yritys-postiosoite (-> maksaja-Yritys :postiosoite) => truthy
        ;; maksajan henkilotieto-osa
        rooliKoodi-maksajan-vastuuhenkilo "maksajan vastuuhenkil\u00f6"
        maksaja-filter-fn #(= (-> % :Vastuuhenkilo :rooliKoodi) rooliKoodi-maksajan-vastuuhenkilo)
        maksaja-Vastuuhenkilo (:Vastuuhenkilo (first (filter maksaja-filter-fn vastuuhenkilot-vec)))
        maksaja-Vastuuhenkilo-osoite (-> maksaja-Vastuuhenkilo :osoitetieto :osoite) => truthy

        ;; Testataan muunnosfunktiota yksityisella maksajalla ("henkilo"-tyyppinen maksaja)
        maksaja-yksityinen (tools/unwrapped
                             (get-yritys-and-henkilo (assoc-in (:data maksaja) [:_selected :value] "henkilo") "maksaja"))
        maksaja-yksityinen-Henkilo (-> maksaja-yksityinen :Osapuoli :henkilotieto :Henkilo) => truthy
        maksaja-yksityinen-nimi (:nimi maksaja-yksityinen-Henkilo) => truthy
        maksaja-yksityinen-osoite (:osoite maksaja-yksityinen-Henkilo) => truthy

        ;; Tapahtuman alkupvm ja loppupvm
        alkuPvm (-> Mainostus-viitoituslupa :alkuPvm) => truthy
        loppuPvm (-> Mainostus-viitoituslupa :loppuPvm) => truthy

        Toimintajakso (-> Mainostus-viitoituslupa :toimintajaksotieto :Toimintajakso) => truthy
        mainostuksen-alku-pvm (-> Toimintajakso :alkuHetki) => truthy
        mainostuksen-loppu-pvm (-> Toimintajakso :loppuHetki) => truthy

        ;; :lupaAsianKuvaus and :sijoituslupaviitetieto do not appear
        lupaAsianKuvaus (:lupaAsianKuvaus Mainostus-viitoituslupa) => falsey
        Sijoituslupaviite (-> Mainostus-viitoituslupa :sijoituslupaviitetieto :Sijoituslupaviite) => falsey

        osapuolet-vec (-> Mainostus-viitoituslupa :osapuolitieto) => truthy

        ;; hakijan yritystieto-osa
        rooliKoodi-Hakija "hakija"
        hakija-osapuoli-filter-fn #(= (-> % :Osapuoli :rooliKoodi) rooliKoodi-Hakija)
        hakija-Osapuoli (:Osapuoli (first (filter hakija-osapuoli-filter-fn osapuolet-vec)))
        hakija-Yritys (-> hakija-Osapuoli :yritystieto :Yritys) => truthy
        hakija-yritys-Postiosoite (-> hakija-Yritys :postiosoitetieto :Postiosoite) => truthy
        ;; hakijan henkilotieto-osa
        rooliKoodi-hankkeen-vastuuhenkilo "hankkeen vastuuhenkil\u00f6"
        hakija-vastuuhenkilo-filter-fn #(= (-> % :Vastuuhenkilo :rooliKoodi) rooliKoodi-hankkeen-vastuuhenkilo)
        hakija-Vastuuhenkilo (:Vastuuhenkilo (first (filter hakija-vastuuhenkilo-filter-fn vastuuhenkilot-vec)))
        hakija-Vastuuhenkilo-osoite (-> hakija-Vastuuhenkilo :osoitetieto :osoite) => truthy

        lisatieto-vec (-> Mainostus-viitoituslupa :lupakohtainenLisatietotieto) => truthy

        pinta-ala (:pintaala Mainostus-viitoituslupa) => falsey

        match-fn #(= "Tapahtuman nimi" (-> % :LupakohtainenLisatieto :selitysteksti))
        tapahtuman-nimi-Lisatieto (:LupakohtainenLisatieto (first (filter match-fn lisatieto-vec))) => truthy
        tapahtuman-nimi (:arvo tapahtuman-nimi-Lisatieto) => truthy

        match-fn #(= "Tapahtumapaikka" (-> % :LupakohtainenLisatieto :selitysteksti))
        tapahtumapaikka-Lisatieto (:LupakohtainenLisatieto (first (filter match-fn lisatieto-vec))) => truthy
        tapahtumapaikka (:arvo tapahtumapaikka-Lisatieto) => truthy

        match-fn #(= "Haetaan kausilupaa" (-> % :LupakohtainenLisatieto :selitysteksti))
        haetaan-kausilupaa-Lisatieto (:LupakohtainenLisatieto (first (filter match-fn lisatieto-vec))) => truthy
        haetaan-kausilupaa (:arvo haetaan-kausilupaa-Lisatieto) => truthy

        ;; Testataan muunnosfunktiota myos "viitoitus tapahtuma" valittuna
        canonical-2 (application-to-canonical viitoitus-application "fi")
        lupaAsianKuvaus-2 (:lupaAsianKuvaus canonical-2) => falsey
        Sijoituslupaviite-2 (:sijoituslupaviitetieto canonical-2) => falsey
        toimintajaksotieto-2 (:toimintajaksotieto canonical-2) => falsey

        Mainostus-viitoituslupa-2 (-> canonical-2 :YleisetAlueet :yleinenAlueAsiatieto :Kayttolupa) => truthy
        lisatieto-vec-2 (-> Mainostus-viitoituslupa-2 :lupakohtainenLisatietotieto) => truthy
        match-fn #(= "Haetaan kausilupaa" (-> % :LupakohtainenLisatieto :selitysteksti))
        haetaan-kausilupaa-Lisatieto (:LupakohtainenLisatieto (first (filter match-fn lisatieto-vec-2))) => falsey]


;    (println "\n canonical:")
;    (clojure.pprint/pprint canonical)
;    (println "\n")

    (fact "contains nil" (contains-value? canonical nil?) => falsey)

    (fact "Kasittelytieto-muutosHetki" (:muutosHetki Kasittelytieto) => (to-xml-datetime (:modified mainostus-application)))
    (fact "Kasittelytieto-hakemuksenTila" (:hakemuksenTila Kasittelytieto) => "vireill\u00e4")
    (fact "Kasittelytieto-asiatunnus" (:asiatunnus Kasittelytieto) => (:id mainostus-application))
    (fact "Kasittelytieto-paivaysPvm" (:paivaysPvm Kasittelytieto) => (to-xml-date (:opened mainostus-application)))
    (fact "Kasittelytieto-kasittelija-etunimi" (:etunimi Kasittelytieto-kasittelija-nimi) => (:firstName sonja))
    (fact "Kasittelytieto-kasittelija-sukunimi" (:sukunimi Kasittelytieto-kasittelija-nimi) => (:lastName sonja))

    (fact "Mainostus-viitoituslupa-kayttotarkoitus" Mainostus-viitoituslupa-kayttotarkoitus => ((keyword (:name operation)) ya-operation-type-to-usage-description))

    ;; Sijainti
    (fact "Sijainti-yksilointitieto" Sijainti-yksilointitieto => (:id mainostus-application))
    (fact "Sijainti-osoitenimi" Sijainti-osoitenimi => (:address mainostus-application))
    (fact "Sijainti-piste-xy" Sijainti-piste => (str (-> mainostus-application :location :x) " " (-> mainostus-application :location :y)))

    ;; Maksajan tiedot
    (fact "maksaja-laskuviite" (:laskuviite Maksaja) => (:value _laskuviite))
    (fact "maksaja-rooliKoodi" (:rooliKoodi maksaja-Vastuuhenkilo) => rooliKoodi-maksajan-vastuuhenkilo)
    (fact "maksaja-henkilo-etunimi" (:etunimi maksaja-Vastuuhenkilo) => (-> nimi :etunimi :value))
    (fact "maksaja-henkilo-sukunimi" (:sukunimi maksaja-Vastuuhenkilo) => (-> nimi :sukunimi :value))
    (fact "maksaja-henkilo-sahkopostiosoite" (:sahkopostiosoite maksaja-Vastuuhenkilo) => (-> yhteystiedot :email :value))
    (fact "maksaja-henkilo-puhelinnumero" (:puhelinnumero maksaja-Vastuuhenkilo) => (-> yhteystiedot :puhelin :value))
    (fact "maksaja-henkilo-osoite-osoitenimi"
      (-> maksaja-Vastuuhenkilo-osoite :osoitenimi :teksti) => (-> osoite :katu :value))
    (fact "maksaja-henkilo-osoite-postinumero"
      (:postinumero maksaja-Vastuuhenkilo-osoite) => (-> osoite :postinumero :value))
    (fact "maksaja-henkilo-osoite-postitoimipaikannimi"
      (:postitoimipaikannimi maksaja-Vastuuhenkilo-osoite) => (-> osoite :postitoimipaikannimi :value))
    (fact "maksaja-yritys-nimi" (:nimi maksaja-Yritys) => (-> yritys-nimi-ja-tunnus :yritysnimi :value))
    (fact "maksaja-yritys-liikeJaYhteisotunnus" (:liikeJaYhteisotunnus maksaja-Yritys) => (-> yritys-nimi-ja-tunnus :liikeJaYhteisoTunnus :value))
    (fact "maksaja-yritys-osoitenimi" (-> maksaja-Yritys-postiosoite :osoitenimi :teksti) => (-> osoite :katu :value))
    (fact "maksaja-yritys-postinumero" (:postinumero maksaja-Yritys-postiosoite) => (-> osoite :postinumero :value))
    (fact "maksaja-yritys-postitoimipaikannimi" (:postitoimipaikannimi maksaja-Yritys-postiosoite) => (-> osoite :postitoimipaikannimi :value))

    ;; Osapuoli: Hakija
    (fact "hakija-vastuuhenkilo-rooliKoodi" (:rooliKoodi hakija-Vastuuhenkilo) => rooliKoodi-hankkeen-vastuuhenkilo)
    (fact "hakija-henkilo-etunimi" (:etunimi hakija-Vastuuhenkilo) => (-> nimi :etunimi :value))
    (fact "hakija-henkilo-sukunimi" (:sukunimi hakija-Vastuuhenkilo) => (-> nimi :sukunimi :value))
    (fact "hakija-henkilo-sahkopostiosoite" (:sahkopostiosoite hakija-Vastuuhenkilo) => (-> yhteystiedot :email :value))
    (fact "hakija-henkilo-puhelinnumero" (:puhelinnumero hakija-Vastuuhenkilo) => (-> yhteystiedot :puhelin :value))
    (fact "hakija-henkilo-osoite-osoitenimi"
      (-> hakija-Vastuuhenkilo-osoite :osoitenimi :teksti) => (-> osoite :katu :value))
    (fact "hakija-henkilo-osoite-postinumero"
      (:postinumero hakija-Vastuuhenkilo-osoite) => (-> osoite :postinumero :value))
    (fact "hakija-henkilo-osoite-postitoimipaikannimi"
      (:postitoimipaikannimi hakija-Vastuuhenkilo-osoite) => (-> osoite :postitoimipaikannimi :value))
    (fact "hakija-osapuoli-rooliKoodi" (:rooliKoodi hakija-Osapuoli) => rooliKoodi-Hakija)
    (fact "hakija-yritys-nimi" (:nimi hakija-Yritys) => (-> yritys-nimi-ja-tunnus :yritysnimi :value))
    (fact "hakija-yritys-liikeJaYhteisotunnus" (:liikeJaYhteisotunnus hakija-Yritys) => (-> yritys-nimi-ja-tunnus :liikeJaYhteisoTunnus :value))
    (fact "hakija-yritys-osoitenimi" (-> hakija-yritys-Postiosoite :osoitenimi :teksti) => (-> osoite :katu :value))
    (fact "hakija-yritys-postinumero" (:postinumero hakija-yritys-Postiosoite) => (-> osoite :postinumero :value))
    (fact "hakija-yritys-postitoimipaikannimi" (:postitoimipaikannimi hakija-yritys-Postiosoite) => (-> osoite :postitoimipaikannimi :value))

    ;; Hakija, yksityinen henkilo -> Tama on testattu jo kohdassa "Maksaja, yksityinen henkilo" (muunnos on taysin sama)

    ;; Mainostus/viitoitustapahtuman alku-/loppupvm
    (fact "alkuPvm" alkuPvm => (to-xml-date-from-string (-> tapahtuma-info :data :mainostus-tapahtuma-valinta :tapahtuma-aika-alkaa-pvm :value)))
    (fact "loppuPvm" loppuPvm => (to-xml-date-from-string (-> tapahtuma-info :data :mainostus-tapahtuma-valinta :tapahtuma-aika-paattyy-pvm :value)))

    ;; Toimintajakso, mainostuksen alku- ja loppuhetki
    (fact "mainostuksen-alku-pvm" mainostuksen-alku-pvm => (to-xml-datetime-from-string (-> tapahtuma-info :data :mainostus-tapahtuma-valinta :mainostus-alkaa-pvm :value)))
    (fact "mainostuksen-loppu-pvm" mainostuksen-loppu-pvm => (to-xml-datetime-from-string (-> tapahtuma-info :data :mainostus-tapahtuma-valinta :mainostus-paattyy-pvm :value)))

    ;; Lisatiedot
    (fact "tapahtuman-nimi" tapahtuman-nimi => (-> tapahtuma-info :data :mainostus-tapahtuma-valinta :tapahtuman-nimi :value))
    (fact "tapahtumapaikka" tapahtumapaikka => (-> tapahtuma-info :data :mainostus-tapahtuma-valinta :tapahtumapaikka :value))
    (fact "haetaan-kausilupaa" haetaan-kausilupaa => (-> tapahtuma-info :data :mainostus-tapahtuma-valinta :haetaan-kausilupaa :value))))



