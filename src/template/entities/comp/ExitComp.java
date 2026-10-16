package template.entities.comp;

import ent.anno.Annotations.*;
import mindustry.gen.*;

import static arc.Core.*;

@EntityComponent
abstract class ExitComp implements Healthc{
    @Override
    public void killed(){
        app.exit();
    }

    /**
     * El motor MX-Test (MindustryX) añade {@code healthChanged()} a Healthc, pero los comps que descarga EntityAnno son los del
     * Mindustry oficial y no lo implementan. Sin esto, el ExitUnit generado no compila ("does not override abstract method healthChanged()").
     */
    public void healthChanged(){}
}
