import Image from "next/image";

const industries = [
  { name: "IT Industry",            img: "/industries/1.png" },
  { name: "Healthcare",             img: "/industries/2.png" },
  { name: "Finance",                img: "/industries/3.png" },
  { name: "Retail",                 img: "/industries/4.png" },
  { name: "Marketing",              img: "/industries/5.png" },
  { name: "Network Infrastructure", img: "/industries/6.png" },
  { name: "Data Science",           img: "/industries/7.png" },
  { name: "Robotics & Automation",  img: "/industries/8.png" },
];

export function IndustriesSection() {
  return (
    <section id="industries" className="relative py-20 lg:py-28 bg-ink-50">
      <div className="container">
        <div className="max-w-3xl mb-14">
          <span className="inline-block text-xs font-semibold uppercase tracking-[0.2em] text-brand mb-3">
            Industries
          </span>
          <h2>Solutions with the <span className="text-brand">adaptability</span> to meet each sector&apos;s unique demands.</h2>
        </div>

        <div className="grid gap-5 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4">
          {industries.map(item => (
            <div
              key={item.name}
              className="group relative overflow-hidden rounded-3xl bg-white shadow-card hover:shadow-cardHover transition-all"
            >
              <div className="aspect-[4/3] overflow-hidden">
                <Image
                  src={item.img}
                  alt={item.name}
                  width={400}
                  height={300}
                  className="h-full w-full object-cover transition-transform duration-500 group-hover:scale-110"
                />
              </div>
              <div className="absolute inset-0 bg-gradient-to-t from-ink-900/85 via-ink-900/20 to-transparent" />
              <h3 className="absolute bottom-5 left-5 right-5 text-xl text-white">{item.name}</h3>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
