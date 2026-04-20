#FAIL:
AG(Busy=1 -> AF(Busy=0));
#PASS:
AG(Busy=1 -> EF(Busy=0));
#PASS:
AG(WCtrlDataStart=1 -> AX Busy=1);
#PASS:
AG(WCtrlDataStart=1 -> AX MdoEn=1);
#PASS:
AG(RStatStart=1 -> AX Busy=1);
#PASS:
AG(RStatStart=1 -> AX MdoEn=1);
#PASS:
AG(MdoEn=1 -> Busy=1);
#FAIL:
AG(Busy=1 -> MdoEn=1);
#PASS:
AG(EndBusy=1 -> AX Busy=0);
#PASS:
AG(EndBusy=1 -> AX EndBusy=0);
#FAIL:
AG(SyncStatMdcEn=1 + AF Busy=0);
