package io.kneo.broadcaster.model.cnst;

import io.kneo.core.localization.LanguageCode;

public enum LanguageTag {

    EN_US("en-US"),
    EN_GB("en-GB"),

    PT_PT("pt-PT"),
    PT_BR("pt-BR"),

    RU_RU("ru-RU"),
    RU_KZ("ru-KZ"),

    KK_KZ("kk-KZ"),

    DE_DE("de-DE"),
    FR_FR("fr-FR"),
    ES_ES("es-ES"),
    IT_IT("it-IT"),

    LV_LV("lv-LV"),
    FI_FI("fi-FI"),
    NO_NO("no-NO"),
    SV_SE("sv-SE"),
    PL_PL("pl-PL"),

    TR_TR("tr-TR"),
    KA_GE("ka-GE"),

    JA_JP("ja-JP"),
    ZH_CN("zh-CN"),
    KO_KR("ko-KR"),

    HI_IN("hi-IN"),
    UK_UA("uk-UA");

    private final String tag;

    LanguageTag(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    public static LanguageTag fromTag(String tag) {
        for (LanguageTag lt : values()) {
            if (lt.tag.equalsIgnoreCase(tag)) {
                return lt;
            }
        }
        throw new IllegalArgumentException("Unsupported language tag: " + tag);
    }

    public LanguageCode toLanguageCode() {
        return switch (this) {
            case EN_US, EN_GB -> LanguageCode.en;
            case PT_PT, PT_BR -> LanguageCode.pt;
            case RU_RU, RU_KZ -> LanguageCode.ru;
            case KK_KZ -> LanguageCode.kk;
            case UK_UA -> LanguageCode.uk;
            case DE_DE -> LanguageCode.de;
            case FR_FR -> LanguageCode.fr;
            case ES_ES -> LanguageCode.es;
            case IT_IT -> LanguageCode.it;
            case LV_LV -> LanguageCode.lv;
            case FI_FI -> LanguageCode.fi;
            case NO_NO -> LanguageCode.no;
            case SV_SE -> LanguageCode.sv;
            case PL_PL -> LanguageCode.pl;
            case TR_TR -> LanguageCode.tr;
            case KA_GE -> LanguageCode.ka;
            case JA_JP -> LanguageCode.ja;
            case ZH_CN -> LanguageCode.zh;
            case KO_KR -> LanguageCode.ko;
            case HI_IN -> LanguageCode.hi;
        };
    }

}
