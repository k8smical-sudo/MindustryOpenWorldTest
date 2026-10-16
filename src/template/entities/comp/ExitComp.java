package template.entities.comp;

import arc.struct.*;
import ent.anno.Annotations.*;
import mindustry.entities.units.*;
import mindustry.gen.*;

import static arc.Core.*;

@EntityComponent
abstract class ExitComp implements Healthc{
    @Override
    public void killed(){
        app.exit();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // El motor MX-Test (MindustryX) añade métodos a las interfaces generadas (Healthc, Shieldc, Statusc), pero EntityAnno
    // genera ExitUnit a partir de los comps del Mindustry OFICIAL, que no los implementan. Sin estos stubs el ExitUnit
    // generado no compila ("does not override abstract method ..."). ExitUnit es solo de plantilla: no hace falta lógica real.
    // Parches de MX que los añaden: 0065 (healthChanged), 0058 (healthBalance, statuses).
    // ---------------------------------------------------------------------------------------------------------------
    public void healthChanged(){}

    public float healthBalance(){
        return 0f;
    }

    public Seq<StatusEntry> statuses(){
        return new Seq<>();
    }
}
