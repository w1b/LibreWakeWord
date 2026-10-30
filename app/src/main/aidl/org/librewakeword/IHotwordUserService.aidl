package org.librewakeword;

interface IHotwordUserService {
    void launchInstrumentation(boolean openUi) = 1;
    void destroy() = 16777114;
}
