AG(((lamp = lit)->EX(lamp=unlit))*((lamp=unlit)->EX(lamp=lit)));
EF((int.state=go3)+(int.state=go4));
EF EG((int.state=go3)+(int.state=go4));
!EF EG((int.state=go3)+(int.state=go4));
AG AF(!(int.state=go3)*!(int.state=go4));
AG((interpretation = go) -> (train = absent));
AG((interpretation = go) -> !(train = present2));
