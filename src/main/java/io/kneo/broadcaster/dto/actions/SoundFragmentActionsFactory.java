package io.kneo.broadcaster.dto.actions;

import com.semantyca.core.dto.actions.ActionBox;
import com.semantyca.core.dto.actions.ActionsFactory;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.IRole;

import java.util.List;

public class SoundFragmentActionsFactory {

    public static ActionBox getViewActions(List<IRole> activatedRoles) {
        ActionBox actions = ActionsFactory.getDefaultViewActions(LanguageCode.en);
        /*Action action = new Action();
        action.setIsOn(RunMode.ON);
        action.setCaption("new_project");
        actions.addAction(action);*/
        return actions;
    }

}
