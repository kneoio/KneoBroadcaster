package io.kneo.broadcaster.model.cnst;

import com.fasterxml.jackson.annotation.JsonValue;
import io.kneo.core.localization.LanguageCode;

public enum LanguageTag {

    // English variants
    EN_US("en-US"),
    EN_GB("en-GB"),
    EN_AU("en-AU"),
    EN_IN("en-IN"),

    // Portuguese
    PT_PT("pt-PT"),
    PT_BR("pt-BR"),

    // Russian & Slavic
    RU_RU("ru-RU"),
    UK_UA("uk-UA"),
    BG_BG("bg-BG"),
    CS_CZ("cs-CZ"),
    HR_HR("hr-HR"),
    PL_PL("pl-PL"),
    SK_SK("sk-SK"),
    SL_SI("sl-SI"),
    SR_RS("sr-RS"),

    // Germanic
    DE_DE("de-DE"),
    NL_NL("nl-NL"),
    NL_BE("nl-BE"),
    SV_SE("sv-SE"),
    NO_NO("no-NO"),
    NB_NO("nb-NO"),
    DA_DK("da-DK"),
    FI_FI("fi-FI"),

    // Romance
    FR_FR("fr-FR"),
    FR_CA("fr-CA"),
    ES_ES("es-ES"),
    ES_US("es-US"),
    IT_IT("it-IT"),
    RO_RO("ro-RO"),
    PT_PT_ALT("pt-PT"), // alias if needed

    // Baltic
    LV_LV("lv-LV"),
    LT_LT("lt-LT"),
    ET_EE("et-EE"),

    // Turkic & Caucasian
    TR_TR("tr-TR"),
    KK_KZ("kk-KZ"),
    KA_GE("ka-GE"),

    // East Asian
    JA_JP("ja-JP"),
    ZH_CN("zh-CN"),
    CMN_CN("cmn-CN"),
    CMN_TW("cmn-TW"),
    YUE_HK("yue-HK"),
    KO_KR("ko-KR"),

    // South Asian
    HI_IN("hi-IN"),
    BN_IN("bn-IN"),
    GU_IN("gu-IN"),
    KN_IN("kn-IN"),
    ML_IN("ml-IN"),
    MR_IN("mr-IN"),
    PA_IN("pa-IN"),
    TA_IN("ta-IN"),
    TE_IN("te-IN"),
    UR_IN("ur-IN"),

    // Southeast Asian
    ID_ID("id-ID"),
    MS_MY("ms-MY"),
    FIL_PH("fil-PH"),
    TH_TH("th-TH"),
    VI_VN("vi-VN"),

    // Middle Eastern
    AR_XA("ar-XA"),
    HE_IL("he-IL"),

    // African
    AM_ET("am-ET"),
    SW_KE("sw-KE"),

    // Greek
    EL_GR("el-GR"),

    // Hungarian
    HU_HU("hu-HU");

    private final String tag;

    LanguageTag(String tag) {
        this.tag = tag;
    }

    @JsonValue
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
            case EN_US, EN_GB, EN_AU, EN_IN -> LanguageCode.en;
            case PT_PT, PT_BR, PT_PT_ALT -> LanguageCode.pt;
            case RU_RU -> LanguageCode.ru;
            case KK_KZ -> LanguageCode.kk;
            case UK_UA -> LanguageCode.uk;
            case DE_DE -> LanguageCode.de;
            case FR_FR, FR_CA -> LanguageCode.fr;
            case ES_ES, ES_US -> LanguageCode.es;
            case IT_IT -> LanguageCode.it;
            case LV_LV -> LanguageCode.lv;
            case FI_FI -> LanguageCode.fi;
            case NO_NO, NB_NO -> LanguageCode.no;
            case SV_SE -> LanguageCode.sv;
            case PL_PL -> LanguageCode.pl;
            case TR_TR -> LanguageCode.tr;
            case KA_GE -> LanguageCode.ka;
            case JA_JP -> LanguageCode.ja;
            case ZH_CN, CMN_CN, CMN_TW, YUE_HK -> LanguageCode.zh;
            case KO_KR -> LanguageCode.ko;
            case HI_IN -> LanguageCode.hi;
            case AM_ET -> LanguageCode.am;
            case AR_XA -> LanguageCode.ar;
            case BG_BG -> LanguageCode.bg;
            case BN_IN -> LanguageCode.bn;
            case CS_CZ -> LanguageCode.cs;
            case DA_DK -> LanguageCode.da;
            case EL_GR -> LanguageCode.el;
            case ET_EE -> LanguageCode.et;
            case FIL_PH -> LanguageCode.fil;
            case GU_IN -> LanguageCode.gu;
            case HE_IL -> LanguageCode.he;
            case HR_HR -> LanguageCode.hr;
            case HU_HU -> LanguageCode.hu;
            case ID_ID -> LanguageCode.id;
            case KN_IN -> LanguageCode.kn;
            case LT_LT -> LanguageCode.lt;
            case ML_IN -> LanguageCode.ml;
            case MR_IN -> LanguageCode.mr;
            case MS_MY -> LanguageCode.ms;
            case NL_NL, NL_BE -> LanguageCode.nl;
            case PA_IN -> LanguageCode.pa;
            case RO_RO -> LanguageCode.ro;
            case SK_SK -> LanguageCode.sk;
            case SL_SI -> LanguageCode.sl;
            case SR_RS -> LanguageCode.sr;
            case SW_KE -> LanguageCode.sw;
            case TA_IN -> LanguageCode.ta;
            case TE_IN -> LanguageCode.te;
            case TH_TH -> LanguageCode.th;
            case UR_IN -> LanguageCode.ur;
            case VI_VN -> LanguageCode.vi;
        };
    }
}